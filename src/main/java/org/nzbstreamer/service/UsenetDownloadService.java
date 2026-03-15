package org.nzbstreamer.service;

import org.apache.logging.log4j.LogManager;
import org.example.NNTPClientFactory;
import org.nzbstreamer.decoder.records.YencHeader;
import org.nzbstreamer.decoder.records.YencPartInfo;
import org.nzbstreamer.utils.NzbUtils;
import org.apache.commons.net.nntp.NNTPClient;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.decoder.MultiPartDecoder;
import org.nzbstreamer.model.DownloadResult;
import org.nzbstreamer.model.NzbFile;
import org.nzbstreamer.model.Segment;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

public class UsenetDownloadService {
    private static final Logger log = LogManager.getLogger(UsenetDownloadService.class);
    private final NNTPClient client;

    public UsenetDownloadService(NNTPClient client) {
        this.client = client;
    }

    public DownloadResult downloadFile(NzbFile file, OutputStream outputStream) throws IOException {
        var fileName = NzbUtils.sanitizeFileName(file.getSubject());
        var group = file.getGroups().getGroup().getFirst();

        if (!selectNewsgroup(group)) {
            throw new IOException("Failed to select group: " + group);
        }


        var segments = file.getSegments().getSegment();
        System.out.println("Downloading (async): " + fileName);

        final Semaphore semaphore = new Semaphore(10); // Limit to 40 concurrent tasks

        List<Future<TempSegment>> futures;

        try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<TempSegment>> callables = new ArrayList<>();
            for (var segment: segments){
                callables.add(() -> {
                    semaphore.acquireUninterruptibly();
                    try {
                        return downloadAndDecodeSegment(segment, segments.size(), group);
                    }catch (Exception e){
                        throw new RuntimeException(e);
                    }finally {
                        semaphore.release();
                    }
                });
            }

            futures = executorService.invokeAll(callables);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        // Wait for all segments and collect temp files
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

        // Concatenate temp files into final output
        for (File temp : tempFiles.values()) {
            Files.copy(temp.toPath(), outputStream);
            temp.delete();
        }

        return DownloadResult.success(fileName);
    }

    private TempSegment downloadAndDecodeSegment(Segment segment, int totalSegments, String group) throws IOException {
        var messageId = NzbUtils.normalizeMessageId(segment.getValue());
        System.out.printf("  Segment %d/%d: %s (async)%n", segment.getNumber(), totalSegments, messageId);

        // Create a new NNTPClient for this thread
        NNTPClient localClient = NNTPClientFactory.getAuthenticatedClient();
        if(!localClient.selectNewsgroup(group)){
            System.err.println(localClient.getReplyString());
            throw new IOException("Failed to select group: " + group);
        }

        Reader reader = localClient.retrieveArticle(messageId);
        if (reader == null) {
            throw new IOException("Article not found: " + messageId +
                    " (Reply: " + localClient.getReplyCode() + " - " + localClient.getReplyString() + ")");
        }

        MultiPartDecoder decoder = new MultiPartDecoder();
        byte[] decoded = decoder.decode(reader);

        // Use a dedicated temp directory in the system temp dir
        File sysTempDir = new File(System.getProperty("java.io.tmpdir"));
        File tempDir = new File(sysTempDir, "nzb-segments");
        if (!tempDir.exists()) tempDir.mkdirs();
        File tempFile = File.createTempFile("segment_" + segment.getNumber() + "_", ".tmp", tempDir);

        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(decoded);
        }

        localClient.disconnect();

        return new TempSegment(segment.getNumber().intValue(), tempFile);
    }

    public byte[] downloadAndDecodeSegment(Segment segment, String group) throws IOException {
        var messageId = NzbUtils.normalizeMessageId(segment.getValue());

        if(!client.selectNewsgroup(group)){
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
        log.debug("Decoded segment " + segment.getNumber() + " size: " + decoded.length + " Segment size: " + segment.getBytes());

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try  {
            bos.write(decoded);
        } finally {
            bos.close();
        }

        log.debug("Downloaded segment " + segment.getNumber() + " size: " + decoded.length + " Segment size: " + segment.getBytes());

        return bos.toByteArray();
    }

    public void populateNzbFileSizes(NzbFile file) throws Exception {
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

    }



    private boolean selectNewsgroup(String group) throws IOException {
        var selected = client.selectNewsgroup(group);
        log.debug("Selected group " + group + ": " + selected);
        return selected;
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
