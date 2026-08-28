package org.nzbstreamer.workers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.exceptions.ArticleUnavaliableException;
import org.nzbstreamer.exceptions.UsenetException;
import org.nzbstreamer.model.Segment;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.service.SegmentFetcher;

import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class DownloadSegmentsDynamicWorker implements Runnable {

    private static final Logger log = LogManager.getLogger(DownloadSegmentsDynamicWorker.class);

    private static final int MAX_RETRIES = 3;

    private final int workerId;
    private final long startPosition;
    private final VirtualFile file;
    private final BlockingQueue<byte[]> bufferQueue;
    private final ArrayDeque<Future<byte[]>> cache = new ArrayDeque<>();
    private final AtomicBoolean endOfSegments;
    private final AtomicBoolean running;
    private final AtomicLong positionInQueue;
    private final SegmentFetcher fetcher;
    private final int maxRetries;
    private final int bufferSize;
    private boolean fastMode = true;

    /**
     * @param workerId      identifies this worker in the logs: several can exist for one file, one
     *                      after another, when the reader seeks.
     * @param startPosition the position of the file for the first byte
     * @param endOfSegments the worker sets this value when it stops. The stream then knows that no
     *                      more bytes come, and it does not wait.
     */
    public DownloadSegmentsDynamicWorker(int workerId, long startPosition, VirtualFile file,
                                        BlockingQueue<byte[]> bufferQueue,
                                        AtomicBoolean endOfSegments, AtomicBoolean running,
                                        SegmentFetcher fetcher, int maxRetries, AtomicLong positionInQueue) {
        this.workerId = workerId;
        this.startPosition = startPosition;
        this.file = file;
        this.bufferQueue = bufferQueue;
        this.endOfSegments = endOfSegments;
        this.positionInQueue = positionInQueue;
        this.running = running;
        this.fetcher = fetcher;
        this.maxRetries = maxRetries;
        this.bufferSize = 64 * 1024; // 64 KB
    }

    @Override
    public void run() {
        log.debug("{}: worker {} starts at position {}", file.filename(), workerId, startPosition);
        ExecutorService downloads = Executors.newFixedThreadPool(4, task -> {
            Thread thread = new Thread(task, "download-" + workerId);
            thread.setDaemon(true);
            return thread;
        });
        try {
            long position = startPosition;
            positionInQueue.set(position);

            // The very first segment: if it is already cached, this returns almost instantly (a
            // local slice, no network). If not, it streams into the queue in small chunks as it
            // downloads, for a fast start, and caches it for next time. Every later segment goes
            // through the cache below.
            if (file.hasNext(position) && running.get()) {
                VirtualFile.Location location = file.locate(position);
                log.debug("{}: worker {} downloads segment {} in fast mode", file.filename(),
                        workerId, location.segment().getValue());
                streamFirstSegment(location);
                position += location.bytesLeftInSegment();
                positionInQueue.set(position);
            }

            while (file.hasNext(position) && running.get()) {
                // Keeps up to PARALLEL_DOWNLOADS segments downloading at the same time, each one
                // submitted only once -- never the segment the fast path already handled.
                while (cache.size() < 4 && file.hasNext(position) && running.get()) {
                    VirtualFile.Location location = file.locate(position);
                    log.debug("{}: worker {} downloads segment {} in background", file.filename(),
                            workerId, location.segment().getValue());
                    cache.add(downloads.submit(() -> bytesOf(location)));
                    position += location.bytesLeftInSegment();
                    positionInQueue.set(position);
                }
                if (!running.get()) {
                    break;
                }
                log.debug("{}: worker {} gives a segment from cache", file.filename(), workerId);
                bufferQueue.put(Objects.requireNonNull(cache.poll()).get());
            }
            // The file ran out before the cache did: give what already finished or is in flight.
            while (!cache.isEmpty() && running.get()) {
                bufferQueue.put(Objects.requireNonNull(cache.poll()).get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("{}: worker {} interrupted", file.filename(), workerId);
        } catch (IOException | UsenetException e) {
            log.error("{}: worker {} stopped because of an error", file.filename(), workerId, e);
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } finally {
            endOfSegments.set(true);
            running.set(false);
            downloads.shutdown();
            // shutdown and not shutdownNow: the downloads that started must give their bytes to
            // the queue. shutdownNow stops them with an interrupt, and the stream then loses the
            // last segments of the file.
            log.debug("{}: worker {} stopped", file.filename(), workerId);
        }
    }

    /**
     * Streams the first segment straight into the queue, in small chunks as it downloads --
     * unlike {@link #bytesOf(VirtualFile.Location)}, which waits for the whole segment. A cache
     * hit still resolves instantly either way, since the streaming fetch checks the cache first.
     */
    private void streamFirstSegment(VirtualFile.Location location)
            throws IOException, InterruptedException, UsenetException {
        Segment segment = location.segment();
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                fetcher.fetch(segment.getValue(), location.group(), bufferQueue, bufferSize,
                        location.byteInSegment(), location.bytesLeftInSegment());
                return;
            } catch (ClosedByInterruptException e) {
                // A move of the cursor stops the downloads that it does not need. A retry of this
                // download gives bytes that nobody reads.
                log.debug("{}: download of segment {} stopped", file.filename(), segment.getNumber());
                throw e;
            } catch (ArticleUnavaliableException e) {
                // The server does not have the article. Another attempt gives the same answer.
                throw e;
            } catch (IOException | UsenetException e) {
                if (attempt == maxRetries || Thread.currentThread().isInterrupted()) {
                    throw e;
                }
                log.warn("Retry {}/{} for segment {} due to: {}", attempt, maxRetries,
                        segment.getNumber(), e.getMessage());
                Thread.sleep(100L * attempt);
            }
        }
        throw new IOException("Max retries exceeded for segment " + segment.getNumber());
    }

    /** Downloads one segment and gives just the bytes of the file that are in it. */
    private byte[] bytesOf(VirtualFile.Location location)
            throws IOException, InterruptedException, UsenetException {
        Segment segment = location.segment();
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return fetcher.fetch(segment.getValue(), location.group(), location.byteInSegment(),
                        location.bytesLeftInSegment());
            } catch (ClosedByInterruptException e) {
                // A move of the cursor stops the downloads that it does not need. A retry of this
                // download gives bytes that nobody reads.
                log.debug("{}: download of segment {} stopped", file.filename(), segment.getNumber());
                throw e;
            } catch (ArticleUnavaliableException e) {
                // The server does not have the article. Another attempt gives the same answer.
                throw e;
            } catch (IOException | UsenetException e) {
                if (attempt == maxRetries || Thread.currentThread().isInterrupted()) {
                    throw e;
                }
                log.warn("Retry {}/{} for segment {} due to: {}", attempt, maxRetries,
                        segment.getNumber(), e.getMessage());
                Thread.sleep(100L * attempt);
            }
        }
        throw new IOException("Max retries exceeded for segment " + segment.getNumber());
    }

}
