package org.workers;

import org.apache.commons.net.nntp.NNTPClient;
import org.apache.logging.log4j.Logger;
import org.example.webdav.VirtualFile;
import org.model.Nzb;
import org.service.UsenetAsyncDownloadService;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class DownloadSegmentsWorker implements Callable<Boolean> {
    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(DownloadSegmentsWorker.class);

    private static final String SERVER = "YOUR_USENET_SERVER";
    private static final int PORT = 119;
    private static final String USERNAME = "YOUR_USENET_USERNAME";
    private static final String PASSWORD = "YOUR_USENET_PASSWORD";


    private final AtomicInteger segmentIndex;
    private final VirtualFile file;
    private final BlockingQueue<byte[]> bufferQueue;
    private final AtomicBoolean endOfSegments;
    private final AtomicBoolean running;
    private final UsenetAsyncDownloadService downloadService;

    public DownloadSegmentsWorker(AtomicInteger segmentIndex, VirtualFile file, BlockingQueue<byte[]> bufferQueue, AtomicBoolean endOfSegments, AtomicBoolean running) {
        this.segmentIndex = segmentIndex;
        this.file = file;
        this.bufferQueue = bufferQueue;
        this.endOfSegments = endOfSegments;
        this.running = running;

        NNTPClient nntpClient = null;
        try {
            nntpClient = initNntpClient();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.downloadService = new UsenetAsyncDownloadService(nntpClient, "temp");

    }

    @Override
    public Boolean call() {
        int segments = file.getNzbFile().getSegments().getSegment().size();

        // Start from current segment index (set by seek)
        int currentSegment = 0;
        long bytesDownloaded = 0;

        while ((currentSegment = segmentIndex.get()) < segments && running.get() && !Thread.currentThread().isInterrupted()) {
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
        return true;
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
}
