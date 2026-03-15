package org.nzbstreamer.streams;

import net.sf.sevenzipjbinding.SevenZipException;
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

    private final long fileSize;
    private long position = 0;
    private final BlockingQueue<byte[]> bufferQueue = new LinkedBlockingQueue<>();
    private byte[] currentChunk = null;
    private int chunkPos = 0;
    private VirtualFile file;// 64 KB
    private AtomicInteger segmentIndex = new AtomicInteger(0);
    private AtomicBoolean endOfSegments = new AtomicBoolean(false);

    private AtomicBoolean running = new AtomicBoolean(false);
    private final long length;


    private DownloadSegmentsWorker downloadWorker;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Future<Boolean> runningTask;



    public VirtualFileInputStream(VirtualFile file) {
        this.file = file;
        this.fileSize = file.getSize();
        this.length = fileSize;
        this.segmentIndex.set(file.getSegmentAtPosition(position));
        int offsetInSegment = Math.toIntExact((int) file.getOffset() - this.file.getNzbFile().getSegment(segmentIndex.get()).getStartPosition());
        if (offsetInSegment > 0) {
            chunkPos = offsetInSegment;
        }
    }

    @Override
    public int read() throws IOException {

        if(position >= fileSize) {
            log.debug("Missing bytes at end of file: " + (fileSize - position));
            return -1;
        }

        if (endOfSegments.get() && bufferQueue.isEmpty() && (currentChunk == null || chunkPos >= currentChunk.length)) {
            log.info("Missing bytes at end of file: " + (fileSize - position));
            return -1;
        }

        if (!running.get() && (currentChunk == null || chunkPos >= currentChunk.length)) {
            downloadWorker = new DownloadSegmentsWorker(segmentIndex, file, bufferQueue, endOfSegments, running);
            log.debug("Executer shutdown? " + (executor.isShutdown() || executor.isTerminated()));
            running.set(true);
            runningTask = executor.submit(downloadWorker);
        }

        if(currentChunk == null || chunkPos >= currentChunk.length) {
            try {
                currentChunk = bufferQueue.take();
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
        //TODO fix skip to work properly skipping without reading all data
        // Update thread to start downloading from the new position
        long actualSkip = Math.min(n, fileSize - position);
        stopAndClearDownloadThread();
        position += actualSkip;
        segmentIndex.set(file.getSegmentAtPosition(position));

        long offsetInSegment = position - file.getNzbFile().getSegment(segmentIndex.get()).getStartPosition() - file.getOffset();
        if (offsetInSegment > 0) {
            currentChunk = null; // Force reload of segment
            chunkPos = (int) offsetInSegment; // Will need to skip within segment
        }
        downloadWorker = new DownloadSegmentsWorker(segmentIndex, file, bufferQueue, endOfSegments, running);
        running.set(true);
        runningTask = executor.submit(downloadWorker);
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

    public synchronized long seek(long offset, int seekOrigin) throws SevenZipException {
        long newPos;
        if (seekOrigin == 0) { // SEEK_SET
            newPos = offset;
        } else if (seekOrigin == 1) { // SEEK_CUR
            newPos = position + offset;
        } else if (seekOrigin == 2) { // SEEK_END
            newPos = length - offset;
        } else {
            throw new SevenZipException("Unsupported seek origin: " + seekOrigin);
        }

        if (newPos < 0) {
            throw new SevenZipException("Seek before begin: " + newPos);
        }
        if (newPos > length) {
            newPos = length;
        }


        // If seeking to a different segment, reset download state
        int newSegmentIndex = file.getSegmentAtPosition(newPos);
        int newChunkPos = Math.toIntExact(newPos - file.getNzbFile().getSegment(newSegmentIndex).getStartPosition());
        if (newSegmentIndex != segmentIndex.get() || newPos < position) {
            log.debug("Seeking from position {} (segment {}) to {} (segment {})",
                    position, segmentIndex, newPos, newSegmentIndex);
            // Stop current download thread
            stopAndClearDownloadThread();
            chunkPos = newChunkPos;
            endOfSegments.set(false);

            // Update position and segment
            position = newPos;
            segmentIndex.set(newSegmentIndex);

            downloadWorker = new DownloadSegmentsWorker(segmentIndex, file, bufferQueue, endOfSegments, running);
            running.set(true);
            runningTask = executor.submit(downloadWorker);

        } else {
            // Forward seek within same segment - just update position
            position = newPos;
        }

        return position;
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
    }
}
