package org.example.webdav;

import org.service.UsenetAsyncDownloadService;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class OnDemandNzbInputStream extends InputStream {

    private final long fileSize;
    private long position = 0;
    private final BlockingQueue<byte[]> bufferQueue = new LinkedBlockingQueue<>();
    private byte[] currentChunk = null;
    private int chunkPos = 0;
    private final int chunkSize = 739502;
    private VirtualFile file;// 64 KB
    private boolean started = false;
    public OnDemandNzbInputStream(VirtualFile file) {
        this.file = file;
        this.fileSize = file.getSize();
    }

    @Override
    public int read() throws IOException {

        if (position >= fileSize) return -1;
        if (!started) {
            started = true;
            new Thread(this::downloadNzbSegments).start();
        }

        ensureChunkAvailable();
        int b = currentChunk[chunkPos++] & 0xFF;
        position++;
        if (chunkPos >= currentChunk.length) currentChunk = null;
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (position >= fileSize) return -1;
        if (!started) {
            started = true;
            new Thread(this::downloadNzbSegments).start();
        }
        int totalRead = 0;
        while (len > 0 && position < fileSize) {
            ensureChunkAvailable();
            int bytesAvailable = currentChunk.length - chunkPos;
            int bytesToRead = Math.min(bytesAvailable, len);
            System.arraycopy(currentChunk, chunkPos, b, off, bytesToRead);
            chunkPos += bytesToRead;
            position += bytesToRead;
            off += bytesToRead;
            len -= bytesToRead;
            totalRead += bytesToRead;
            if (chunkPos >= currentChunk.length) currentChunk = null;
        }
        return totalRead;
    }

    @Override
    public long skip(long n) {
        long skipped = Math.min(n, fileSize - position);
        position += skipped;
        currentChunk = null;
        return skipped;
    }

    public VirtualFile getFile() {
        return file;
    }

    private void ensureChunkAvailable() throws IOException {
        if (currentChunk == null) {
            try {
                currentChunk = bufferQueue.take();
                chunkPos = 0;
            } catch (InterruptedException e) {
                throw new IOException("Interrupted while waiting for NZB data", e);
            }
        }
    }

    private void downloadNzbSegments() {
        long downloaded = 0;
        int segmentIndex = 0;
        while (downloaded < fileSize) {
            int size = (int) Math.min(chunkSize, fileSize - downloaded);
            byte[] chunk = new byte[size];

            // Simulate NZB segment download (replace with real NZB decoding)
            try {
                if (bufferQueue.isEmpty()) {
                    chunk = UsenetAsyncDownloadService.downloadAndDecodeSegment(file.getNzbFile().getSegments().getSegment().get(segmentIndex), file.getNzbFile().getGroups().getGroup().getFirst());
                    segmentIndex++;
                } else {
                    continue;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            try {
                bufferQueue.put(chunk);
            } catch (InterruptedException e) {
                // Handle interruption if needed
                break;
            }

            downloaded += size;
        }
    }
}
