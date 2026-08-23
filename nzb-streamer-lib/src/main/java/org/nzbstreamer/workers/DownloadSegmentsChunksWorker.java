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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class DownloadSegmentsChunksWorker implements Runnable {

    private static final Logger log = LogManager.getLogger(DownloadSegmentsChunksWorker.class);

    private static final int MAX_RETRIES = 3;

    private final int workerId;
    private final long startPosition;
    private final VirtualFile file;
    private final BlockingQueue<byte[]> bufferQueue;
    private final AtomicBoolean endOfSegments;
    private final AtomicBoolean running;
    private final SegmentFetcher fetcher;
    private final int maxRetries;
    private final int bufferSize;

    /**
     * @param workerId      identifies this worker in the logs: several can exist for one file, one
     *                      after another, when the reader seeks.
     * @param startPosition the position of the file for the first byte
     * @param endOfSegments the worker sets this value when it stops. The stream then knows that no
     *                      more bytes come, and it does not wait.
     */
    public DownloadSegmentsChunksWorker(int workerId, long startPosition, VirtualFile file,
                                  BlockingQueue<byte[]> bufferQueue,
                                  AtomicBoolean endOfSegments, AtomicBoolean running,
                                  SegmentFetcher fetcher, int maxRetries, int bufferSize) {
        this.workerId = workerId;
        this.startPosition = startPosition;
        this.file = file;
        this.bufferQueue = bufferQueue;
        this.endOfSegments = endOfSegments;
        this.running = running;
        this.fetcher = fetcher;
        this.maxRetries = maxRetries;
        this.bufferSize = bufferSize;
    }

    @Override
    public void run() {
        log.debug("{}: worker {} starts at position {}", file.filename(), workerId, startPosition);
        try {
            long position = startPosition;
            while (file.hasNext(position) && running.get()) {
                if (!running.get()) {
                    break;
                }
                // The location gives the segment, the first byte of the file in it and the number
                // of bytes of the file in it. Thus the worker needs no calculation.
                VirtualFile.Location location = file.locate(position);
                bytesOf(location);
                position += location.bytesLeftInSegment();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("{}: worker {} interrupted", file.filename(), workerId);
        } catch (IOException | UsenetException e) {
            log.error("{}: worker {} stopped because of an error", file.filename(), workerId, e);
            throw new RuntimeException(e);
        } finally {
            endOfSegments.set(true);
            running.set(false);
            // shutdown and not shutdownNow: the downloads that started must give their bytes to
            // the queue. shutdownNow stops them with an interrupt, and the stream then loses the
            // last segments of the file.
            log.debug("{}: worker {} stopped", file.filename(), workerId);
        }
    }

    /** Downloads one segment and gives the bytes of the file that are in it. */
    private void bytesOf(VirtualFile.Location location)
            throws IOException, InterruptedException, UsenetException {
        long startedAt = System.nanoTime();

        // The bytes outside the location are the bytes of the archive. They stay out.
//        int from = ;
//        int end = Math.min(from + location.bytesLeftInSegment(), bytes.length);
        downloadWithRetry(location, location.byteInSegment(), location.bytesLeftInSegment(), maxRetries);

//        log.debug("{}: segment {} ready in {} ms, {} bytes of {}", file.filename(),
//                location.segment().getValue(), (System.nanoTime() - startedAt) / 1_000_000,
//                used.length, bytes.length);
//        return used;
    }

    private void downloadWithRetry(VirtualFile.Location location, int skip, int trim, int maxRetries)
            throws IOException, InterruptedException, UsenetException {
        Segment segment = location.segment();
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                fetcher.fetch(segment.getValue(), location.group(), bufferQueue, bufferSize, skip, trim);
//                if (downloaded.length != segment.getSize()) {
//                    log.warn("segment {} has {} bytes, but the map says {}. The positions after it"
//                                    + " are possibly wrong.", segment.getValue(), downloaded.length,
//                            segment.getSize());
//                }
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
            } finally {
                log.debug("{}: worker {} finished segment {}, attempt {}/{}", file.filename(),
                        workerId, segment.getNumber(), attempt, maxRetries);
            }
        }
        throw new IOException("Max retries exceeded for segment " + segment.getNumber());
    }
}
