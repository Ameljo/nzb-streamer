package org.nzbstreamer.workers;

import org.apache.logging.log4j.Logger;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.repository.ApplicationContextUtil;
import org.nzbstreamer.service.UsenetDownloadService;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class DownloadSegmentsWorker implements Callable<Boolean> {
    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(DownloadSegmentsWorker.class);
    private static final int MIN_BUFFER_SIZE = 4;
    private static final int MAX_RETRIES = 3;
    private static final int BUFFER_FULL_SLEEP_MS = 10;

    private final AtomicInteger segmentIndex;
    private final VirtualFile file;
    private final BlockingQueue<byte[]> bufferQueue;
    private final AtomicBoolean endOfSegments;
    private final AtomicBoolean running;
    private final UsenetDownloadService downloadService;

    public DownloadSegmentsWorker(AtomicInteger segmentIndex, VirtualFile file,
                                  BlockingQueue<byte[]> bufferQueue,
                                  AtomicBoolean endOfSegments,
                                  AtomicBoolean running) {
        this.segmentIndex = segmentIndex;
        this.file = file;
        this.bufferQueue = bufferQueue;
        this.endOfSegments = endOfSegments;
        this.running = running;
        this.downloadService = ApplicationContextUtil.getBean(UsenetDownloadService.class);
    }

    @Override
    public Boolean call() {
        try {
            return downloadSegments();
        } catch (Exception e) {
            log.error("Unexpected error in DownloadSegmentsWorker", e);
            return false;
        }
    }

    private Boolean downloadSegments() {
        int segments = file.getNzbFile().getSegments().getSegment().size();
        int currentSegment;
        long bytesDownloaded = 0;

        log.debug("Starting download from segment {}/{}", segmentIndex.get(), segments);

        while ((currentSegment = segmentIndex.get()) < segments && running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                if (bufferQueue.size() >= MIN_BUFFER_SIZE) {
                    Thread.sleep(BUFFER_FULL_SLEEP_MS);
                    continue;
                }

                byte[] chunk = downloadSegmentWithRetry(currentSegment, MAX_RETRIES);
                bufferQueue.put(chunk);
                bytesDownloaded += chunk.length;
                segmentIndex.incrementAndGet();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Thread interrupted during segment download", e);
                return false;
            } catch (IOException e) {
                log.error("IO error downloading segment {}", currentSegment, e);
                return false;
            }
        }

        endOfSegments.set(true);
        running.set(false);
        log.debug("Thread finished downloading segments. Total bytes downloaded out of: {}/{}", bytesDownloaded, file.getSize());
        return true;
    }

    private byte[] downloadSegmentWithRetry(int segmentIndex, int maxRetries) throws IOException, InterruptedException {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return downloadService.downloadAndDecodeSegment(
                        file.getNzbFile().getSegments().getSegment().get(segmentIndex),
                        file.getNzbFile().getGroups().getGroup().getFirst()
                );
            } catch (IOException e) {
                if (attempt == maxRetries) {
                    throw e;
                }
                log.warn("Retry {}/{} for segment {} due to: {}", attempt, maxRetries, segmentIndex, e.getMessage());
                Thread.sleep(100L * attempt);
            }
        }
        throw new IOException("Max retries exceeded for segment " + segmentIndex);
    }
}
