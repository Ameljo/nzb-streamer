package org.nzbstreamer.streams;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.service.SegmentFetcher;
import org.nzbstreamer.workers.DownloadSegmentsChunksWorker;
import org.nzbstreamer.workers.DownloadSegmentsWorker;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class StreamingSource extends AbstractSegmentSource {

    private static final Logger log = LogManager.getLogger(StreamingSource.class);

    static final int MAX_RETRIES = 3;
    private static final int PARALLEL_DOWNLOADS = 8;
    static final int BUFFER_SIZE = 64 * 1024;

    private final VirtualFile file;
    private final SegmentFetcher fetcher;
    private final int maxRetries;
    private final int bufferSize;
    private final String owner = callerClass();

    /**
     * The queue and the flags of the worker that runs now.
     *
     * <p>A move of the cursor makes a new worker with new objects. Thus this class does not wait
     * for the old worker: that worker writes in its own queue, and nobody reads it.</p>
     */
    private BlockingQueue<byte[]> bufferQueue = null;
    private AtomicBoolean endOfSegments = new AtomicBoolean(false);
    private AtomicBoolean running = new AtomicBoolean(false);
    private int queueSize = 0;

    /** The position of the file for the first byte that the next window will hold. */
    private long nextStart;
    private boolean started;

    /**
     * Gives each worker of every source a unique id, so the logs can tell them apart even across
     * concurrent requests for the same file. A counter on one source would restart at 1 for every
     * request, and every request's "worker 1" would look like the same worker in the logs.
     */
    private static final AtomicInteger WORKER_SEQ = new AtomicInteger();

    private int currentWorkerId;

    public StreamingSource(VirtualFile file, SegmentFetcher fetcher) {
        this(file, fetcher, MAX_RETRIES, BUFFER_SIZE);
    }

    public StreamingSource(VirtualFile file, SegmentFetcher fetcher, int maxRetries) {
        this(file, fetcher, maxRetries, BUFFER_SIZE);
    }

    public StreamingSource(VirtualFile file, SegmentFetcher fetcher, int maxRetries, int bufferSize) {
        this.file = file;
        this.fetcher = fetcher;
        this.maxRetries = maxRetries;
        this.bufferSize = bufferSize;
        this.queueSize = maxChunksAhead(file, bufferSize);
    }

    /**
     * Chunks of 6 segments, sized from this file's own first segment. Falls back to one chunk
     * per segment when the file has no segments to measure -- there is nothing to download
     * ahead of in that case, so the exact size does not matter.
     */
    private static int maxChunksAhead(VirtualFile file, int bufferSize) {
        boolean hasSegments = !file.getChunks().isEmpty()
                && !file.getChunks().getFirst().segments().isEmpty();
        long segmentSize = hasSegments
                ? file.getChunks().getFirst().segments().getFirst().getSize()
                : bufferSize;
        return Math.toIntExact((segmentSize * 6) / bufferSize) + 1;
    }

    @Override
    protected Window fetchWindow(long position) throws IOException {
        if (!started || position != nextStart) {
            log.debug("{}: worker {} was serving this source (started={}, nextStart={}), replacing"
                    + " it at {}", file.filename(), currentWorkerId, started, nextStart, position);
            startWorkerAt(position, bufferSize);
        }
        // The worker stopped and the queue is empty. No more bytes come, thus a wait here would
        // not stop.
        if (endOfSegments.get() && bufferQueue.isEmpty()) {
            log.error("{}: worker {} stopped at position {} of {}, thus the file is not complete",
                    file.filename(), currentWorkerId, position, file.getSize());
            return null;
        }

        byte[] bytes;
        try {
            bytes = bufferQueue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for data", e);
        }

        nextStart = position + bytes.length;
        return bytes.length == 0 ? null : new Window(bytes, position, bytes.length);
    }

    @Override
    protected void onSeek(long position) {
        // The worker downloads the segments of the position of before. The next call of
        // fetchWindow() starts a worker at the new position.
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
    private void startWorkerAt(long startPosition, int bufferSize) {
        stopWorker();

        bufferQueue = new LinkedBlockingQueue<>(queueSize);
        endOfSegments = new AtomicBoolean(false);
        running = new AtomicBoolean(true);
        nextStart = startPosition;
        started = true;
        currentWorkerId = WORKER_SEQ.incrementAndGet();

        log.debug("{}: starting worker {} at {}", file.filename(), currentWorkerId, startPosition);
        Thread worker = new Thread(new DownloadSegmentsChunksWorker(currentWorkerId, startPosition,
                file, bufferQueue, endOfSegments, running, fetcher, maxRetries, bufferSize),
                "worker-" + owner + "-" + currentWorkerId);
        worker.setDaemon(true);
        worker.start();
    }

    /** Tells the worker to stop. It does not wait for it. */
    private void stopWorker() {
        running.set(false);
        if (bufferQueue != null) {
            bufferQueue.clear();
        }
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
