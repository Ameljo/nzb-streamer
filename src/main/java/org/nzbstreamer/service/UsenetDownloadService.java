package org.nzbstreamer.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.decoder.MultiPartDecoder;
import org.nzbstreamer.exceptions.UsenetException;
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
    private final UsenetConnectionPool pool;

    @Autowired
    public UsenetDownloadService(NNTPClientFactory clientFactory, UsenetConnectionPool pool) {
        this.pool = pool;
    }

    public DownloadResult downloadFile(NzbFile file, OutputStream outputStream) throws IOException {
        var fileName = NzbUtils.sanitizeFileName(file.getSubject());
        var group = file.getGroups().getGroup().getFirst();

        var segments = file.getSegments().getSegment();
        System.out.println("Downloading (async): " + fileName);

        final Semaphore semaphore = new Semaphore(10);

        List<Future<TempSegment>> futures;

        try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<TempSegment>> callables = getCallables(segments, semaphore, group);
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

    private List<Callable<TempSegment>> getCallables(List<Segment> segments, Semaphore semaphore, String group) {
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
        return callables;
    }

    private TempSegment downloadAndDecodeSegment(Segment segment, int totalSegments, String group) throws IOException, InterruptedException, UsenetException {
        var messageId = NzbUtils.normalizeMessageId(segment.getValue());
        System.out.printf("  Segment %d/%d: %s (async)%n", segment.getNumber(), totalSegments, messageId);

        byte[] decoded;
        PooledClient pooled = pool.borrow(group);
        boolean healthy = false;
        try {
            try (Reader reader = pooled.retrieveArticle(messageId)) {
                decoded = new MultiPartDecoder().decode(reader);
            }
            healthy = true;
        } finally {
            if (healthy) {
                pool.release(pooled);
            } else {
                pool.discard(pooled);
            }
        }

        File sysTempDir = new File(System.getProperty("java.io.tmpdir"));
        File tempDir = new File(sysTempDir, "nzb-segments");
        if (!tempDir.exists()) tempDir.mkdirs();
        File tempFile = File.createTempFile("segment_" + segment.getNumber() + "_", ".tmp", tempDir);

        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(decoded);
        }

        return new TempSegment(segment.getNumber().intValue(), tempFile);
    }

    public byte[] downloadAndDecodeSegment(Segment segment, String group) throws IOException, InterruptedException, UsenetException {
        var messageId = NzbUtils.normalizeMessageId(segment.getValue());

        // TRACE: the times below show the cost of each step: the connection of the pool, which
        // holds the selection of the group, and the transfer.
        long borrowStart = System.nanoTime();
        PooledClient pooled = pool.borrow(group);
        long borrowMs = millisecondsSince(borrowStart);

        byte[] decoded;
        long transferMs;
        boolean healthy = false;
        try {
            long transferStart = System.nanoTime();
            try (Reader reader = pooled.retrieveArticle(messageId)) {
                decoded = new MultiPartDecoder().decode(reader);
            }
            transferMs = millisecondsSince(transferStart);
            healthy = true;
        } finally {
            if (healthy) {
                pool.release(pooled);
            } else {
                pool.discard(pooled);
            }
        }

        log.debug("Decoded segment " + segment.getNumber() + " size: " + decoded.length + " Segment size: " + segment.getBytes());
        log.debug("segment {}: {} bytes in {} ms = connection {} ms + transfer {} ms",
                segment.getNumber(), decoded.length, borrowMs + transferMs, borrowMs, transferMs);

        return decoded;
    }

    private static long millisecondsSince(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
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
