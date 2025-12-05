package org.example.webdav;

import net.sf.sevenzipjbinding.IInStream;
import net.sf.sevenzipjbinding.SevenZipException;
import org.apache.commons.net.nntp.NNTPClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.service.UsenetAsyncDownloadService;
import org.workers.DownloadSegmentsWorker;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class OnDemandNzbInputStream extends InputStream {

    private static final Logger log = LogManager.getLogger(OnDemandNzbInputStream.class);

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



    private static final String SERVER = "YOUR_USENET_SERVER";
    private static final int PORT = 119;
    private static final String USERNAME = "YOUR_USENET_USERNAME";
    private static final String PASSWORD = "YOUR_USENET_PASSWORD";

    private DownloadSegmentsWorker downloadWorker;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Future<Boolean> runningTask;


    private final NNTPClient client;

    public OnDemandNzbInputStream(VirtualFile file) {
        this.file = file;
        this.fileSize = file.getSize();
        this.length = fileSize;
        client = new NNTPClient();
//        try {
//            client = initNntpClient();
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }
    }

    @Override
    public int read() throws IOException {

        if(position >= fileSize)
            return -1;

        if (endOfSegments.get() && bufferQueue.isEmpty() && (currentChunk == null || chunkPos >= currentChunk.length)) {
            System.out.println("Missing bytes at end of file: " + (fileSize - position));
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
                chunkPos = 0;
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
        position += actualSkip;
        segmentIndex.set(file.getNzbFile().getSegmentAtPosition(position));

        long offsetInSegment = position - file.getNzbFile().getSegments().getSegment().get(segmentIndex.get()).getStartPosition();
        if (offsetInSegment > 0) {
            currentChunk = null; // Force reload of segment
            chunkPos = (int) offsetInSegment; // Will need to skip within segment
        }

        return actualSkip;
    }

    public VirtualFile getFile() {
        return file;
    }

    private NNTPClient initNntpClient() throws IOException {
        NNTPClient client = new NNTPClient();
        client.connect(SERVER, PORT);
        log.debug("Connected to NNTP server: " + SERVER + ":" + PORT);
        if (!client.authenticate(USERNAME, PASSWORD)) {
            throw new IOException("Failed to login to NNTP server: " + client.getReplyString());
        }
        log.debug("Authenticated to NNTP server as user: " + USERNAME);
        return client;
    }

    private void downloadNzbSegments() {
        NNTPClient nntpClient = null;
        try {
            nntpClient = initNntpClient();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        UsenetAsyncDownloadService downloadService = new UsenetAsyncDownloadService(nntpClient, "temp");
        int segments = file.getNzbFile().getSegments().getSegment().size();

        // Start from current segment index (set by seek)
        int currentSegment = 0;
        long bytesDownloaded = 0;

        while ((currentSegment = segmentIndex.get()) < segments && running.get()) {
            try {
                if (bufferQueue.isEmpty() || bufferQueue.size() < 4) {
                    byte[] chunk = downloadService.downloadAndDecodeSegment(
                            file.getNzbFile().getSegments().getSegment().get(currentSegment),
                            file.getNzbFile().getGroups().getGroup().getFirst()
                    );
                    bufferQueue.put(chunk);
                    bytesDownloaded += chunk.length;
                    segmentIndex.incrementAndGet();
                }
            } catch (IOException | InterruptedException e) {
                log.error("Error downloading segment {}", currentSegment, e);
                break;
            }
        }
        endOfSegments.set(true);
        running.set(false);
        log.debug("Thread finished downloading segments. Total bytes downloaded: {}", bytesDownloaded);
    }

    @Override
    public int available() throws IOException {
        return (int) (fileSize - position);
    }

    @Override
    public void close() throws IOException {
        super.close();
        if (client != null && client.isConnected()) {
            client.disconnect();
            log.debug("Disconnected from NNTP server");
        }
        running.set(false);
        bufferQueue.clear();
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
        int newSegmentIndex = file.getNzbFile().getSegmentAtPosition(newPos);
        int newChunkPos = Math.toIntExact(newPos - file.getNzbFile().getSegments().getSegment().get(newSegmentIndex).getStartPosition());
        if (newSegmentIndex != segmentIndex.get() || newPos < position) {
            log.debug("Seeking from position {} (segment {}) to {} (segment {})",
                    position, segmentIndex, newPos, newSegmentIndex);
            // Stop current download thread
            log.debug("Stopping current download thread for seek");
            if(runningTask != null) {
                running.set(false);
                try {
                    boolean interrupted = runningTask.get();
                    log.debug("Download thread terminated: {}", interrupted);
                } catch (ExecutionException | InterruptedException e) {
                    log.warn("Interrupted while waiting for download thread to terminate", e);
                }
            }

            // Clear buffered data
            bufferQueue.clear();
            currentChunk = null;
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
}
