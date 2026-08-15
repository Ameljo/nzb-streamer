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
    private static final int PARALLEL_POSTS = 8;

    private final NNTPClientFactory clientFactory;
    private final UsenetConnectionPool pool;

    @Autowired
    public UsenetDownloadService(NNTPClientFactory clientFactory, UsenetConnectionPool pool) {
        this.clientFactory = clientFactory;
        this.pool = pool;
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

    /** Gives the sizes of several posts at the same time. */
    public void populateNzbFileSizes(List<NzbFile> files) throws Exception {
        if (files.isEmpty()) {
            return;
        }
        ExecutorService workers = Executors.newFixedThreadPool(
                Math.min(PARALLEL_POSTS, files.size()), task -> {
                    Thread thread = new Thread(task, "nzb-size");
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            List<Future<?>> pending = new ArrayList<>();
            for (NzbFile file : files) {
                pending.add(workers.submit(() -> {
                    populateNzbFileSizes(file);
                    return null;
                }));
            }
            for (Future<?> future : pending) {
                future.get();
            }
        } finally {
            workers.shutdown();
        }
    }

    /**
     * Gives each segment of the post its size and its position.
     *
     * <p>One article answers all of it. Its yEnc header gives the size of the file and its ypart
     * line gives the size of a segment. All the segments have that size, except the last one,
     * which holds what stays.</p>
     */
    public void populateNzbFileSizes(NzbFile file) throws Exception {
        long startedAt = System.nanoTime();
        List<Segment> segments = file.getSegments().getSegment();
        String messageId = NzbUtils.normalizeMessageId(segments.getFirst().getValue());
        String group = file.getGroups().getGroup().getFirst();

        UsenetConnectionPool.PooledClient pooled = pool.borrow();
        boolean handedOver = false;
        YencStart start;
        try {
            if (!group.equals(pooled.group())) {
                if (!pooled.client().selectNewsgroup(group)) {
                    throw new IOException("Failed to select group: " + group);
                }
                pooled.group(group);
            }
            Reader reader = pooled.client().retrieveArticle(messageId);
            if (reader == null) {
                throw new IOException("Article not found: " + messageId + " (Reply: "
                        + pooled.client().getReplyCode() + " - " + pooled.client().getReplyString() + ")");
            }
            start = readStart(reader);
            // The two lines are at the start of the article. The pool reads the rest of it on
            // another thread, thus this operation does not wait for it.
            pool.releaseAfterDrain(pooled, reader);
            handedOver = true;
        } finally {
            if (!handedOver) {
                pool.discard(pooled);
            }
        }

        if (start.header() == null) {
            throw new IOException("No yEnc header in " + messageId);
        }
        long total = start.header().size();
        long segmentSize = start.part() == null
                ? total : start.part().end() - start.part().begin() + 1;
        int count = segments.size();
        long last = total - segmentSize * (count - 1);
        if (segmentSize <= 0 || last <= 0) {
            throw new IOException("The sizes of " + messageId + " are not usable: total " + total
                    + ", segment " + segmentSize + ", " + count + " segments");
        }

        long position = 0;
        for (int i = 0; i < count; i++) {
            Segment segment = segments.get(i);
            segment.setSize(i == count - 1 ? last : segmentSize);
            segment.setStartPosition(position);
            position += segment.getSize();
        }
        file.setSize(total);
        log.debug("sizes of {}: {} segments of {} bytes, last {}, total {}, article {} in {} ms",
                NzbUtils.sanitizeFileName(file.getSubject()), count, segmentSize, last, total,
                messageId, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private record YencStart(YencHeader header, YencPartInfo part) {}

    /**
     * Reads the ybegin line and the ypart line of an article, and stops there. The caller must
     * give the reader to {@link UsenetConnectionPool#releaseAfterDrain}.
     */
    private YencStart readStart(Reader reader) throws IOException {
        YencHeader header = null;
        YencPartInfo part = null;
        // Not a try with resources: a close operation reads the rest of the article. The caller
        // gives the reader to the pool, which reads it on another thread.
        BufferedReader lines = new BufferedReader(reader);
        String line;
        while ((line = lines.readLine()) != null) {
            if (header == null && line.startsWith("=ybegin")) {
                header = YencHeader.parse(line);
            } else if (header != null) {
                // The line after ybegin is the ypart line, or the first line of the data when the
                // article holds all the file. Both mean that the answer holds nothing more.
                if (line.startsWith("=ypart")) {
                    part = YencPartInfo.parse(line);
                }
                break;
            }
        }
        return new YencStart(header, part);
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
