package org.example.webdav;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.net.nntp.NNTPClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.service.UsenetAsyncDownloadService;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class OnDemandNzbInputStream extends InputStream {

    private static final Logger log = LogManager.getLogger(OnDemandNzbInputStream.class);

    private final long fileSize;
    private long position = 0;
    private byte[] currentChunk = null;
    private int chunkPos = 0;
    private VirtualFile file;// 64 KB
    private long segmentSize = 1024 * 64;
    private int segmentIndex = 0;


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
        byte[] b = new byte[1];
        return read(b, 0, 1) == -1 ? -1 : b[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {

        if (position >= fileSize) return -1;
        int totalRead = 0;
        int downloadCount = 0;
        if (len > 0 && position < fileSize) {
            boolean isNewSegmentNeeded = (currentChunk == null) || (chunkPos >= currentChunk.length);
            int segmentsNeeded = len / (int) segmentSize + 1;
            if (isNewSegmentNeeded) {
                long start = System.currentTimeMillis();
                currentChunk = new byte[0];
                for (int i = 0; i < segmentsNeeded; i++) {
                    currentChunk = ArrayUtils.addAll(currentChunk, downloadService.downloadAndDecodeSegment(file.getNzbFile().getSegments().getSegment().get(segmentIndex), file.getNzbFile().getGroups().getGroup().getFirst()));
                    log.debug("Downloaded segment in " + (System.currentTimeMillis() - start) + "ms");
                    downloadCount++;
                    segmentIndex++;
                }
                chunkPos = 0;
            }
            int bytesAvailable = currentChunk.length - chunkPos;
            int bytesToRead = Math.min(bytesAvailable, len);
            System.arraycopy(currentChunk, chunkPos, b, off, bytesToRead);
            chunkPos += bytesToRead;
            position += bytesToRead;
            totalRead += bytesToRead;
            len -= bytesToRead;
//            if (chunkPos >= currentChunk.length) currentChunk = null;
        }
        log.debug("read() downloaded " + downloadCount + " segments, returned " + totalRead + " bytes");
        return totalRead;
    }

    @Override
    public long skip(long n) {
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

    @Override
    public void close() throws IOException {
        super.close();
        if (client != null && client.isConnected()) {
            client.disconnect();
            log.debug("Disconnected from NNTP server");
        }
    }
}
