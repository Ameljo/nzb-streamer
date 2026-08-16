package org.nzbstreamer.streams.proto;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.service.SegmentFetcher;
import org.nzbstreamer.workers.DownloadSegmentsWorker;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads the segments that come after the position, on a worker of its own.
 *
 * <p>A player reads a file from one end to the other. This source thus reads segments in advance
 * and puts them in a queue, and a read takes the bytes that already arrived. This is the source of
 * the playback.</p>
 *
 * <p>The source starts no worker before the first call of {@link #at(long)}. Thus a caller that
 * makes a stream and reads no byte makes no connection to the news server.</p>
 *
 * <p>PROTOTYPE. Nothing uses this package yet.</p>
 */
public class PrefetchingSource implements SegmentSource {

    private static final Logger log = LogManager.getLogger(PrefetchingSource.class);

    private static final int MAX_RETRIES = 3;
    private static final int PARALLEL_DOWNLOADS = 8;

    private final VirtualFile file;
    private final SegmentFetcher fetcher;
    private final int maxRetries;
    private final int parallelDownloads;
    private final String owner = callerClass();

    /**
     * The queue and the flags of the worker that runs now.
     *
     * <p>A move of the cursor makes a new worker with new objects. Thus this class does not wait
     * for the old worker: that worker writes in its own queue, and nobody reads it.</p>
     */
    private BlockingQueue<Future<byte[]>> bufferQueue = new LinkedBlockingQueue<>();
    private AtomicBoolean endOfSegments = new AtomicBoolean(false);
    private AtomicBoolean running = new AtomicBoolean(false);

    /** The position of the file for the first byte that the next window will hold. */
    private long nextStart;
    private boolean started;

    public PrefetchingSource(VirtualFile file, SegmentFetcher fetcher) {
        this(file, fetcher, MAX_RETRIES, PARALLEL_DOWNLOADS);
    }

    public PrefetchingSource(VirtualFile file, SegmentFetcher fetcher, int maxRetries,
                             int parallelDownloads) {
        this.file = file;
        this.fetcher = fetcher;
        this.maxRetries = maxRetries;
        this.parallelDownloads = parallelDownloads;
    }

    @Override
    public Window at(long position) throws IOException {
        if (!started || position != nextStart) {
            startWorkerAt(position);
        }
        // The worker stopped and the queue is empty. No more bytes come, thus a wait here would
        // not stop.
        if (endOfSegments.get() && bufferQueue.isEmpty()) {
            log.error("{}: the worker stopped at position {} of {}, thus the file is not complete",
                    file.filename(), position, file.getSize());
            return null;
        }

        byte[] bytes;
        try {
            bytes = bufferQueue.take().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for data", e);
        } catch (ExecutionException e) {
            log.error("{}: cannot download the segment of position {} of {}", file.filename(),
                    position, file.getSize(), e.getCause());
            throw new IOException("Cannot download the segment of position " + position,
                    e.getCause());
        }

        nextStart = position + bytes.length;
        return bytes.length == 0 ? null : new Window(bytes, position, bytes.length);
    }

    @Override
    public void moveTo(long position) {
        // The worker downloads the segments of the position of before. The next call of at()
        // starts a worker at the new position.
        stopWorker();
        started = false;
    }

    @Override
    public void close() {
        stopWorker();
        started = false;
    }

    /**
     * Starts a worker at the given position.
     *
     * <p>This function does not wait for the worker of before. That worker stops when it sees its
     * flag, and it writes in its own queue. Thus a move of the cursor gives the first bytes of the
     * new position after one download, and not after two downloads.</p>
     */
    private void startWorkerAt(long startPosition) {
        stopWorker();

        bufferQueue = new LinkedBlockingQueue<>();
        endOfSegments = new AtomicBoolean(false);
        running = new AtomicBoolean(true);
        nextStart = startPosition;
        started = true;

        Thread worker = new Thread(new DownloadSegmentsWorker(startPosition, file, bufferQueue,
                endOfSegments, running, fetcher, maxRetries, parallelDownloads, owner),
                "worker-" + owner);
        worker.setDaemon(true);
        worker.start();
    }

    /** Tells the worker to stop. It does not wait for it. */
    private void stopWorker() {
        running.set(false);
        for (Future<byte[]> segment : bufferQueue) {
            segment.cancel(true);
        }
        bufferQueue.clear();
    }

    /** The class that made this source, for the logs: the transformer, the scanner, a controller. */
    private static String callerClass() {
        return StackWalker.getInstance().walk(frames -> frames
                .map(StackWalker.StackFrame::getClassName)
                .filter(name -> !name.startsWith("org.nzbstreamer.streams."))
                .filter(name -> !name.equals("org.nzbstreamer.model.VirtualFile"))
                .findFirst()
                .map(name -> name.substring(name.lastIndexOf('.') + 1))
                .orElse("unknown"));
    }
}
