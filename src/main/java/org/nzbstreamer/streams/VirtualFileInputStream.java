package org.nzbstreamer.streams;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.workers.DownloadSegmentsWorker;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class VirtualFileInputStream extends InputStream {

    private static final Logger log = LogManager.getLogger(VirtualFileInputStream.class);

    /**
     * Makes the worker that downloads the segments. The production code uses
     * {@link DownloadSegmentsWorker}. A test supplies segments of its own with this interface.
     */
    @FunctionalInterface
    public interface DownloadWorkerFactory {
        Callable<Boolean> create(AtomicInteger segmentIndex, VirtualFile file,
                                 BlockingQueue<byte[]> bufferQueue, AtomicBoolean endOfSegments,
                                 AtomicBoolean running);
    }

    private final long fileSize;
    private long position = 0;
    private final BlockingQueue<byte[]> bufferQueue = new LinkedBlockingQueue<>();
    private byte[] currentChunk = null;
    private int chunkPos = 0;
    private VirtualFile file;
    private AtomicInteger segmentIndex = new AtomicInteger(0);
    private AtomicBoolean endOfSegments = new AtomicBoolean(false);

    private AtomicBoolean running = new AtomicBoolean(false);
    private final long length;

    /**
     * The segment that {@link #currentChunk} holds. The value is -1 when no chunk is in memory.
     *
     * <p>{@link #segmentIndex} is not usable for this purpose. The worker increments that value
     * after each download. Thus it gives the position of the download, and it can be some segments
     * in front of the position of the read operations.</p>
     */
    private int currentChunkSegment = -1;

    /** The segment of the next chunk in the queue. The worker delivers the segments in sequence. */
    private int nextChunkSegment;

    private final DownloadWorkerFactory workerFactory;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Future<Boolean> runningTask;

    public VirtualFileInputStream(VirtualFile file) {
        this(file, DownloadSegmentsWorker::new);
    }

    public VirtualFileInputStream(VirtualFile file, DownloadWorkerFactory workerFactory) {
        this.file = file;
        this.workerFactory = workerFactory;
        this.fileSize = file.getSize();
        this.length = fileSize;
        int firstSegment = segmentOf(0);
        this.segmentIndex.set(firstSegment);
        this.nextChunkSegment = firstSegment;
        this.chunkPos = offsetInSegment(0, firstSegment);
    }

    /**
     * Changes a position in this file to a position in the NZB file. A virtual file that is in a
     * RAR archive starts at {@link VirtualFile#getOffset()} in the NZB file.
     */
    private long absolutePosition(long streamPosition) {
        return file.getOffset() + streamPosition;
    }

    /** Gives the segment that holds the given position of this file. */
    private int segmentOf(long streamPosition) {
        return file.getNzbFile().getSegmentAtPosition(absolutePosition(streamPosition));
    }

    /** Gives the position in the given segment for the given position of this file. */
    private int offsetInSegment(long streamPosition, int segment) {
        long segmentStart = file.getNzbFile().getSegment(segment).getStartPosition();
        return Math.toIntExact(absolutePosition(streamPosition) - segmentStart);
    }

    @Override
    public int read() throws IOException {

        if (position >= fileSize) {
            log.debug("Missing bytes at end of file: " + (fileSize - position));
            return -1;
        }

        if (endOfSegments.get() && bufferQueue.isEmpty() && (currentChunk == null || chunkPos >= currentChunk.length)) {
            log.info("Missing bytes at end of file: " + (fileSize - position));
            return -1;
        }

        // The queue can hold data after the worker stops. The stream must use that data first. A
        // new worker at this time downloads nothing and does no useful work. The queue must also
        // be empty to keep nextChunkSegment equal to the segment of the next chunk.
        if (!running.get() && bufferQueue.isEmpty()
                && (currentChunk == null || chunkPos >= currentChunk.length)) {
            log.debug("Executer shutdown? " + (executor.isShutdown() || executor.isTerminated()));
            startWorkerAt(segmentIndex.get());
        }

        if (currentChunk == null || chunkPos >= currentChunk.length) {
            try {
                currentChunk = bufferQueue.take();
                currentChunkSegment = nextChunkSegment++;
                if (chunkPos >= currentChunk.length) {
                    chunkPos = 0;
                }
            } catch (InterruptedException e) {
                throw new IOException("Interrupted while waiting for data", e);
            }
        }
        byte b = currentChunk[chunkPos++];
        position++;
        return b & 0xFF;
    }

    @Override
    public long skip(long n) {
        if (n <= 0) {
            return 0;
        }
        long actualSkip = Math.min(n, fileSize - position);
        long target = position + actualSkip;

        if (moveInsideCurrentChunk(target)) {
            log.debug("Skipped {} bytes inside segment {}, new position {}", actualSkip,
                    currentChunkSegment, position);
            return actualSkip;
        }

        restartAt(target);
        log.info("Skipped {} bytes, new position {}", actualSkip, position);
        return actualSkip;
    }

    public VirtualFile getFile() {
        return file;
    }

    @Override
    public int available() throws IOException {
        return (int) (fileSize - position);
    }

    @Override
    public void close() throws IOException {
        super.close();
        executor.shutdown();
        running.set(false);
        bufferQueue.clear();
        log.debug("OnDemandNzbInputStream closed");
    }

    public synchronized long seek(long offset, int seekOrigin) throws IOException {
        long newPos;
        if (seekOrigin == 0) { // SEEK_SET
            newPos = offset;
        } else if (seekOrigin == 1) { // SEEK_CUR
            newPos = position + offset;
        } else if (seekOrigin == 2) { // SEEK_END
            newPos = length - offset;
        } else {
            throw new IOException("Unsupported seek origin: " + seekOrigin);
        }

        if (newPos < 0) {
            throw new IOException("Seek before begin: " + newPos);
        }
        if (newPos > length) {
            newPos = length;
        }

        if (moveInsideCurrentChunk(newPos)) {
            log.debug("Moved to position {} inside segment {}", position, currentChunkSegment);
            return position;
        }

        log.debug("Seeking from position {} (segment {}) to {} (segment {})", position,
                currentChunkSegment, newPos, segmentOf(newPos));
        restartAt(newPos);
        return position;
    }

    /**
     * Moves to the target position in the chunk that is in memory.
     *
     * <p>The function moves the cursor forward and also rearward. It does not download data. It
     * returns false when the target position is in a different segment. The caller must then use
     * {@link #restartAt(long)}.</p>
     */
    private boolean moveInsideCurrentChunk(long target) {
        if (currentChunk == null || currentChunkSegment < 0) {
            return false;
        }
        if (segmentOf(target) != currentChunkSegment) {
            return false;
        }
        int offsetInChunk = offsetInSegment(target, currentChunkSegment);
        if (offsetInChunk < 0 || offsetInChunk > currentChunk.length) {
            return false;
        }
        chunkPos = offsetInChunk;
        position = target;
        return true;
    }

    /** Stops the worker, then starts a new worker at the segment that holds the target position. */
    private void restartAt(long target) {
        stopAndClearDownloadThread();
        // The worker sets endOfSegments when it stops, also when this class stops it. Without this
        // reset the next read() gives -1 and the stream stays at the end of the file.
        endOfSegments.set(false);
        position = target;
        int segment = segmentOf(target);
        chunkPos = offsetInSegment(target, segment);
        startWorkerAt(segment);
    }

    private void startWorkerAt(int segment) {
        segmentIndex.set(segment);
        nextChunkSegment = segment;
        running.set(true);
        runningTask = executor.submit(
                workerFactory.create(segmentIndex, file, bufferQueue, endOfSegments, running));
    }

    private void stopAndClearDownloadThread() {
        if (runningTask != null) {
            running.set(false);
            try {
                boolean interrupted = runningTask.get();
                log.debug("Download thread terminated: {}", interrupted);
            } catch (ExecutionException | InterruptedException e) {
                log.warn("Interrupted while waiting for download thread to terminate", e);
            }
        }
        bufferQueue.clear();
        currentChunk = null;
        currentChunkSegment = -1;
    }
}
