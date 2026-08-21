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
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads the segments of a file in sequence and puts their bytes in a queue.
 *
 * <p>The worker walks the positions of the file. For each position the file gives the segment, the
 * first byte of the file in that segment and the number of bytes of the file in it. The worker
 * thus removes the bytes that the file does not use: the headers at the start of a volume, the
 * last blocks at the end of a volume and the bytes before the position of a move.</p>
 *
 * <p>The worker downloads {@value #PARALLEL_DOWNLOADS} segments at the same time and reads up to
 * {@value #MAX_AHEAD} segments in advance. It puts a {@link Future} of each segment in the queue,
 * thus the sequence of the segments stays correct.</p>
 */
public class DownloadSegmentsWorker implements Runnable {

    private static final Logger log = LogManager.getLogger(DownloadSegmentsWorker.class);

    /** The number of segments in the queue and in the downloads. */
    private static final int MAX_AHEAD = 16;

    /**
     * The number of downloads that run at the same time.
     *
     * <p>A pool of threads and not a thread for each download: the threads take the segments in
     * the sequence of the file. A thread for each download starts them all at the same time, and
     * the segment that the reader waits for then takes a connection after the segments that come
     * after it.</p>
     */
    private static final int PARALLEL_DOWNLOADS = 8;

    private static final int MAX_RETRIES = 3;
    private static final int QUEUE_FULL_SLEEP_MS = 10;

    private final long startPosition;
    private final VirtualFile file;
    private final BlockingQueue<Future<byte[]>> bufferQueue;
    private final AtomicBoolean endOfSegments;
    private final AtomicBoolean running;
    private final SegmentFetcher fetcher;
    private final int maxRetries;
    private final int parallelDownloads;
    private final int maxAhead;
    private final String source;

    /**
     * @param startPosition the position of the file for the first byte
     * @param endOfSegments the worker sets this value when it stops. The stream then knows that no
     *                      more bytes come, and it does not wait.
     */
    public DownloadSegmentsWorker(long startPosition, VirtualFile file,
                                  BlockingQueue<Future<byte[]>> bufferQueue,
                                  AtomicBoolean endOfSegments, AtomicBoolean running,
                                  SegmentFetcher fetcher, int maxRetries,
                                  int parallelDownloads, String source) {
        this.startPosition = startPosition;
        this.file = file;
        this.bufferQueue = bufferQueue;
        this.endOfSegments = endOfSegments;
        this.running = running;
        this.fetcher = fetcher;
        this.maxRetries = maxRetries;
        this.parallelDownloads = parallelDownloads;
        this.maxAhead = parallelDownloads == 1 ? 1 : MAX_AHEAD;
        this.source = source;
    }

    @Override
    public void run() {
        ExecutorService downloads = Executors.newFixedThreadPool(parallelDownloads, task -> {
            Thread thread = new Thread(task, "download-" + source);
            thread.setDaemon(true);
            return thread;
        });
        log.debug("{}: worker starts at position {}", file.filename(), startPosition);

        try {
            long position = startPosition;
            while (file.hasNext(position) && running.get()) {
                while (bufferQueue.size() >= maxAhead && running.get()) {
                    Thread.sleep(QUEUE_FULL_SLEEP_MS);
                }
                if (!running.get()) {
                    break;
                }
                // The location gives the segment, the first byte of the file in it and the number
                // of bytes of the file in it. Thus the worker needs no calculation.
                VirtualFile.Location location = file.locate(position);
                bufferQueue.put(downloads.submit(() -> bytesOf(location)));
                position += location.bytesLeftInSegment();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("{}: worker interrupted", file.filename());
        } finally {
            endOfSegments.set(true);
            running.set(false);
            // shutdown and not shutdownNow: the downloads that started must give their bytes to
            // the queue. shutdownNow stops them with an interrupt, and the stream then loses the
            // last segments of the file.
            downloads.shutdown();
            log.debug("{}: worker stopped", file.filename());
        }
    }

    /** Downloads one segment and gives the bytes of the file that are in it. */
    private byte[] bytesOf(VirtualFile.Location location)
            throws IOException, InterruptedException, UsenetException {
        long startedAt = System.nanoTime();
        byte[] bytes = downloadWithRetry(location, maxRetries);

        // The bytes outside the location are the bytes of the archive. They stay out.
        int from = location.byteInSegment();
        int end = Math.min(from + location.bytesLeftInSegment(), bytes.length);
        byte[] used = from < end ? Arrays.copyOfRange(bytes, from, end) : new byte[0];

        log.debug("{}: segment {} ready in {} ms, {} bytes of {}", file.filename(),
                location.segment().getValue(), (System.nanoTime() - startedAt) / 1_000_000,
                used.length, bytes.length);
        return used;
    }

    private byte[] downloadWithRetry(VirtualFile.Location location, int maxRetries)
            throws IOException, InterruptedException, UsenetException {
        Segment segment = location.segment();
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                byte[] downloaded = fetcher.fetch(segment.getValue(), location.group());
                if (downloaded.length != segment.getSize()) {
                    log.warn("segment {} has {} bytes, but the map says {}. The positions after it"
                                    + " are possibly wrong.", segment.getValue(), downloaded.length,
                            segment.getSize());
                }
                return downloaded;
            } catch (ClosedByInterruptException e) {
                // A move of the cursor stops the downloads that it does not need. A retry of this
                // download gives bytes that nobody reads.
                log.debug("{}: download of {} stopped", file.filename(), segment.getValue());
                throw e;
            } catch (ArticleUnavaliableException e) {
                // The server does not have the article. Another attempt gives the same answer.
                throw e;
            } catch (IOException | UsenetException e) {
                if (attempt == maxRetries || Thread.currentThread().isInterrupted()) {
                    throw e;
                }
                log.warn("Retry {}/{} for {} due to: {}", attempt, maxRetries, segment.getValue(),
                        e.getMessage());
                Thread.sleep(100L * attempt);
            }
        }
        throw new IOException("Max retries exceeded for " + segment.getValue());
    }
}
