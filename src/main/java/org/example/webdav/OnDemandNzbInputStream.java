package org.example.webdav;

import org.apache.commons.net.nntp.NNTPClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.service.UsenetAsyncDownloadService;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class OnDemandNzbInputStream extends InputStream {

    private static final Logger log = LogManager.getLogger(OnDemandNzbInputStream.class);

    private final long fileSize;
    private long position = 0;
    private final BlockingQueue<byte[]> bufferQueue = new LinkedBlockingQueue<>();
    private byte[] currentChunk = null;
    private int chunkPos = 0;
    private VirtualFile file;// 64 KB
    private long segmentSize = 1024 * 64;
    private int segmentIndex = 0;
    private boolean endOfSegments = false;

    private boolean running = false;


    private static final String SERVER = "YOUR_USENET_SERVER";
    private static final int PORT = 119;
    private static final String USERNAME = "YOUR_USENET_USERNAME";
    private static final String PASSWORD = "YOUR_USENET_PASSWORD";


    private NNTPClient client;
    private UsenetAsyncDownloadService downloadService;

    public OnDemandNzbInputStream(VirtualFile file) {
        this.file = file;
        this.fileSize = file.getSize();
        this.segmentSize = file.getNzbFile().getSegments().getSegment().getFirst().getBytes().longValue();
        try {
            client = initNntpClient();
            downloadService = new UsenetAsyncDownloadService(client, "temp");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int read() throws IOException {

        if(position >= fileSize)
            return -1;

        if (endOfSegments && bufferQueue.isEmpty())
            return -1;

        if(!running) {
            new Thread(this::downloadNzbSegments).start();
            running = true;
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
        segmentIndex = (int) (position / segmentSize);

        long offsetInSegment = position % segmentSize;
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
        NNTPClient nntpClient  = null;
        try {
            nntpClient  = initNntpClient();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        UsenetAsyncDownloadService downloadService = new UsenetAsyncDownloadService(nntpClient , "temp");
        int segments = file.getNzbFile().getSegments().getSegment().size();
        int segmentIndex = 0;
        while (segmentIndex < segments && running) {
            byte[] chunk;
            try {
                if (bufferQueue.isEmpty() || bufferQueue.size() < 4) {
                    chunk = downloadService.downloadAndDecodeSegment(file.getNzbFile().getSegments().getSegment().get(segmentIndex), file.getNzbFile().getGroups().getGroup().getFirst());
                    segmentIndex++;
                    bufferQueue.put(chunk);
                }
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        endOfSegments = true;
    }

    @Override
    public void close() throws IOException {
        super.close();
        if (client != null && client.isConnected()) {
            client.disconnect();
            log.debug("Disconnected from NNTP server");
        }
        running = false;
        bufferQueue.clear();
    }
}
