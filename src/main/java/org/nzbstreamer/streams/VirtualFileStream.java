package org.nzbstreamer.streams;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.model.VirtualFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads the bytes of a {@link VirtualFile}.
 *
 * <p>The stream does not know the segments, the chunks, the volumes or the archive: it forwards
 * every read to its {@link SegmentSource}, which owns the window of bytes and the cursor inside
 * it. The source says how it finds the bytes — a worker that reads in advance for a player, or a
 * download for each read for a parser of headers.</p>
 *
 * <p>The stream downloads nothing before the first read. A caller that makes a stream and reads no
 * byte thus makes no connection to the news server.</p>
 */
public class VirtualFileStream extends InputStream {

    private static final Logger log = LogManager.getLogger(VirtualFileStream.class);

    private final VirtualFile file;
    private final SegmentSource source;

    private long position;
    private long markPosition;

    public VirtualFileStream(VirtualFile file, SegmentSource source) {
        this.file = file;
        this.source = source;
    }

    @Override
    public int read() throws IOException {
        if (!file.hasNext(position)) {
            return -1;
        }
        log.trace("{}: read at {}", file.filename(), position);
        int b = source.read();
        if (b >= 0) {
            position++;
        }
        return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (!file.hasNext(position)) {
            return -1;
        }
        log.trace("{}: read at {}, up to {} bytes", file.filename(), position, length);
        int count = source.read(buffer, offset, length);
        if (count > 0) {
            position += count;
        }
        return count;
    }

    /** Moves the cursor. The source decides whether that needs a new download. */
    public void seek(long newPosition) {
        log.debug("{}: seek from {} to {}", file.filename(), position, newPosition);
        position = newPosition;
        source.seek(newPosition);
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

    public VirtualFile getFile() {
        return file;
    }

    @Override
    public void close() throws IOException {
        super.close();
        source.close();
        log.debug("{} closed at position {}", file.filename(), position);
    }
}
