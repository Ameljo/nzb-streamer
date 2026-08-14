package org.nzbstreamer.service;

import org.apache.logging.log4j.LogManager;
import org.apache.commons.net.nntp.NNTPClient;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.decoder.MultiPartDecoder;
import org.nzbstreamer.decoder.records.YencHeader;
import org.nzbstreamer.decoder.records.YencPartInfo;
import org.nzbstreamer.model.DownloadResult;
import org.nzbstreamer.model.NzbFile;
import org.nzbstreamer.model.Segment;
import org.nzbstreamer.utils.NzbUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

@Service
public class UsenetDownloadService {
    private static final Logger log = LogManager.getLogger(UsenetDownloadService.class);
    private final NNTPClientFactory clientFactory;

    @Autowired
    public UsenetDownloadService(NNTPClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    public DownloadResult downloadFile(NzbFile file, OutputStream outputStream) throws IOException {
        var fileName = NzbUtils.sanitizeFileName(file.getSubject());
        var group = file.getGroups().getGroup().getFirst();

        NNTPClient client = clientFactory.createClient();
        try {
            if (!client.selectNewsgroup(group)) {
                throw new IOException("Failed to select group: " + group);
            }
        } finally {
            client.disconnect();
        }

        var segments = file.getSegments().getSegment();
        System.out.println("Downloading (async): " + fileName);

        final Semaphore semaphore = new Semaphore(10);

        List<Future<TempSegment>> futures;

        try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<TempSegment>> callables = new ArrayList<>();
            for (var segment : segments) {
                callables.add(() -> {
                    semaphore.acquireUninterruptibly();
                    try {
                        return downloadAndDecodeSegment(segment, segments.size(), group);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        semaphore.release();
                    }
                });
            }
            futures = executorService.invokeAll(callables);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        Map<Integer, File> tempFiles = new TreeMap<>();
        try {
            for (Future<TempSegment> future : futures) {
                TempSegment temp = future.get();
                tempFiles.put(temp.number, temp.file);
            }
        } catch (Exception e) {
            for (File f : tempFiles.values()) f.delete();
            return DownloadResult.failed(fileName, "Failed to download segment: " + e.getMessage());
        }

        for (File temp : tempFiles.values()) {
            Files.copy(temp.toPath(), outputStream);
            temp.delete();
        }

        return DownloadResult.success(fileName);
    }

    private TempSegment downloadAndDecodeSegment(Segment segment, int totalSegments, String group) throws IOException {
        var messageId = NzbUtils.normalizeMessageId(segment.getValue());
        System.out.printf("  Segment %d/%d: %s (async)%n", segment.getNumber(), totalSegments, messageId);

        NNTPClient client = clientFactory.createClient();
        try {
            if (!client.selectNewsgroup(group)) {
                System.err.println(client.getReplyString());
                throw new IOException("Failed to select group: " + group);
            }

            Reader reader = client.retrieveArticle(messageId);
            if (reader == null) {
                throw new IOException("Article not found: " + messageId +
                        " (Reply: " + client.getReplyCode() + " - " + client.getReplyString() + ")");
            }

            MultiPartDecoder decoder = new MultiPartDecoder();
            byte[] decoded = decoder.decode(reader);

            File sysTempDir = new File(System.getProperty("java.io.tmpdir"));
            File tempDir = new File(sysTempDir, "nzb-segments");
            if (!tempDir.exists()) tempDir.mkdirs();
            File tempFile = File.createTempFile("segment_" + segment.getNumber() + "_", ".tmp", tempDir);

            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(decoded);
            }

            return new TempSegment(segment.getNumber().intValue(), tempFile);
        } finally {
            client.disconnect();
        }
    }

    public byte[] downloadAndDecodeSegment(Segment segment, String group) throws IOException {
        var messageId = NzbUtils.normalizeMessageId(segment.getValue());

        // TRACE: this method makes a new connection for each segment. The times below show the
        // cost of each step: the connection, the selection of the group and the transfer.
        long connectStart = System.nanoTime();
        NNTPClient client = clientFactory.createClient();
        long connectMs = millisecondsSince(connectStart);
        long groupMs;
        long transferMs;
        try {
            long groupStart = System.nanoTime();
            if (!client.selectNewsgroup(group)) {
                System.err.println(client.getReplyString());
                throw new IOException("Failed to select group: " + group);
            }
            groupMs = millisecondsSince(groupStart);

            long transferStart = System.nanoTime();
            Reader reader = client.retrieveArticle(messageId);
            if (reader == null) {
                throw new IOException("Article not found: " + messageId +
                        " (Reply: " + client.getReplyCode() + " - " + client.getReplyString() + ")");
            }

            MultiPartDecoder decoder = new MultiPartDecoder();
            byte[] decoded = decoder.decode(reader);
            transferMs = millisecondsSince(transferStart);
            log.debug("Decoded segment " + segment.getNumber() + " size: " + decoded.length + " Segment size: " + segment.getBytes());
            log.debug("segment {}: {} bytes in {} ms = connect {} ms + group {} ms + transfer {} ms",
                    segment.getNumber(), decoded.length, connectMs + groupMs + transferMs, connectMs,
                    groupMs, transferMs);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bos.write(decoded);
            return bos.toByteArray();
        } finally {
            long disconnectStart = System.nanoTime();
            client.disconnect();
            log.debug("segment {}: disconnect in {} ms", segment.getNumber(),
                    millisecondsSince(disconnectStart));
        }
    }

    private static long millisecondsSince(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    public void populateNzbFileSizes(NzbFile file) throws Exception {
        NNTPClient client = clientFactory.createClient();
        try {
            var messageId = NzbUtils.normalizeMessageId(file.getSegments().getSegment().getFirst().getValue());
            client.selectNewsgroup(file.getGroups().getGroup().getFirst());

            var reader = client.retrieveArticle(messageId);
            if (reader == null) {
                throw new IOException("Article not found: " + messageId +
                        " (Reply: " + client.getReplyCode() + " - " + client.getReplyString() + ")");
            }
            MultiPartDecoder decoder = new MultiPartDecoder();
            YencHeader header = decoder.parseYencHeader(reader);
            file.setSize(header.size());
            reader.close();

            reader = client.retrieveArticle(NzbUtils.normalizeMessageId(file.getSegments().getSegment().getFirst().getValue()));
            YencPartInfo partInfo = decoder.parseYencPartInfo(reader);
            long position = 0;
            for (int i = 0; i < file.getSegments().getSegment().size() - 1; i++) {
                Segment segment = file.getSegment(i);
                segment.setSize(partInfo.end());
                segment.setStartPosition(position);
                position += partInfo.end();
            }

            reader = client.retrieveArticle(NzbUtils.normalizeMessageId(file.getSegments().getSegment().getLast().getValue()));
            partInfo = decoder.parseYencPartInfo(reader);
            long lastSegmentSize = partInfo.end() - partInfo.begin() + 1;
            file.getSegments().getSegment().getLast().setSize(lastSegmentSize);
            file.getSegments().getSegment().getLast().setStartPosition(position);
        } finally {
            client.disconnect();
        }
    }

    private static class TempSegment {
        final int number;
        final File file;
        TempSegment(int number, File file) {
            this.number = number;
            this.file = file;
        }
    }
}
