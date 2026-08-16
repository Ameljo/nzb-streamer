package org.nzbstreamer.streams;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.service.SegmentFetcher;
import org.nzbstreamer.workers.DownloadSegmentsWorker;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads the bytes of a {@link VirtualFile}.
 *
 * <p>The stream reads a sequence of bytes. It does not know the segments, the chunks, the volumes
 * or the archive. The file says if a byte is at the position. The worker gives the bytes.</p>
 *
 * <p>{@link #seek(long)} is the only function that changes the position. It stops the worker and
 * starts a new worker at the new position.</p>
 */
public class VirtualFileInputStream extends InputStream {

    private static final Logger log = LogManager.getLogger(VirtualFileInputStream.class);

    private final VirtualFile file;
    private final SegmentFetcher fetcher;
    private final int maxRetries;
    private final int parallelDownloads;
    private final String source = callerClass();

    /**
     * The queue and the flags of the worker that runs now.
     *
     * <p>A move of the cursor makes a new worker with new objects. Thus this class does not wait
     * for the old worker: that worker writes in its own queue, and nobody reads it.</p>
     */
    private BlockingQueue<Future<byte[]>> bufferQueue = new LinkedBlockingQueue<>();
    private AtomicBoolean endOfSegments = new AtomicBoolean(false);
    private AtomicBoolean running = new AtomicBoolean(false);

    private long position;
    private long markPosition;

    /** The bytes of the queue that the stream reads now. */
    private byte[] currentBytes;
    private int cursor;
    /** The position of the file for the first byte of {@link #currentBytes}. */
    private long currentBytesStart;

    private static final int MAX_RETRIES = 3;
    private static final int PARALLEL_DOWNLOADS = 8;

    public VirtualFileInputStream(VirtualFile file, SegmentFetcher fetcher) {
        this(file, fetcher, MAX_RETRIES, PARALLEL_DOWNLOADS);
    }

    private VirtualFileInputStream(VirtualFile file, SegmentFetcher fetcher, int maxRetries,
                                   int parallelDownloads) {
        this.file = file;
        this.fetcher = fetcher;
        this.maxRetries = maxRetries;
        this.parallelDownloads = parallelDownloads;
        seek(0);
    }

    /** The class that made this stream, for the logs: the transformer, the scanner, a controller. */
    private static String callerClass() {
        return StackWalker.getInstance().walk(frames -> frames
                .map(StackWalker.StackFrame::getClassName)
                .filter(name -> !name.startsWith("org.nzbstreamer.streams."))
                .filter(name -> !name.equals("org.nzbstreamer.model.VirtualFile"))
                .findFirst()
                .map(name -> name.substring(name.lastIndexOf('.') + 1))
                .orElse("unknown"));
    }

    @Override
    public int read() throws IOException {
        if (!file.hasNext(position)) {
            return -1;
        }
        if (currentBytes == null || cursor >= currentBytes.length) {
            // The worker stopped and the queue is empty. No more bytes come, thus a wait here
            // would not stop.
            if (endOfSegments.get() && bufferQueue.isEmpty()) {
                log.error("{}: the worker stopped at position {} of {}, thus the file is not"
                        + " complete", file.filename(), position, file.getSize());
                return -1;
            }
            try {
                currentBytes = bufferQueue.take().get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for data", e);
            } catch (ExecutionException e) {
                log.error("{}: cannot download the segment of position {} of {}", file.filename(),
                        position, file.getSize(), e.getCause());
                throw new IOException("Cannot download the segment of position " + position,
                        e.getCause());
            }
            cursor = 0;
            currentBytesStart = position;
        }
        position++;
        return currentBytes[cursor++] & 0xFF;
    }

    public VirtualFile getFile() {
        return file;
    }

    /**
     * Moves the cursor and starts the worker at the new position.
     *
     * <p>A move inside the bytes that the stream holds now keeps the worker. Tika marks the
     * stream, reads a few bytes and moves back. Without this, that move downloads again the
     * segment that is already in the memory.</p>
     */
    public void seek(long newPosition) {
        if (currentBytes != null && newPosition >= currentBytesStart
                && newPosition < currentBytesStart + currentBytes.length) {
            position = newPosition;
            cursor = (int) (newPosition - currentBytesStart);
            return;
        }
        position = newPosition;
        if (!file.hasNext(position)) {
            stopWorker();
            return;
        }
        startWorkerAt(position);
    }

    @Override
    public long skip(long count) {
        long actual = Math.max(0, Math.min(count, file.getSize() - position));
        seek(position + actual);
        return actual;
    }

    @Override
    public int available() {
        return (int) Math.min(Integer.MAX_VALUE, file.getSize() - position);
    }

    /**
     * This stream supports mark and reset.
     *
     * <p>This is important for Tika. {@code TikaInputStream.get} puts a {@code BufferedInputStream}
     * around a stream without this support. That buffer then reads the bytes of a skip operation
     * instead of a move of the cursor, and a skip across a large file downloads all the segments
     * of the file.</p>
     */
    @Override
    public boolean markSupported() {
        return true;
    }

    @Override
    public synchronized void mark(int readLimit) {
        markPosition = position;
    }

    @Override
    public synchronized void reset() {
        seek(markPosition);
    }

    @Override
    public void close() throws IOException {
        super.close();
        stopWorker();
        log.debug("{} closed at position {}", file.filename(), position);
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
        currentBytes = null;
        cursor = 0;

        Thread worker = new Thread(new DownloadSegmentsWorker(startPosition, file, bufferQueue,
                endOfSegments, running, fetcher, maxRetries, parallelDownloads, source),
                "worker-" + source);
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
}
