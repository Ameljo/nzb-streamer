package org.nzbstreamer.streams;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.model.VirtualFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Reads the bytes of a {@link VirtualFile}.
 *
 * <p>The stream reads a sequence of bytes. It does not know the segments, the chunks, the volumes
 * or the archive: it asks its {@link SegmentSource} for the bytes around a position. The source
 * says how it finds them — a worker that reads in advance for a player, or a download for each
 * read for a parser of headers.</p>
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

    /** The part of the file that the stream holds now, or null. */
    private SegmentSource.Window window;

    public VirtualFileStream(VirtualFile file, SegmentSource source) {
        this.file = file;
        this.source = source;
    }

    @Override
    public int read() throws IOException {
        if (!load()) {
            return -1;
        }
        int index = (int) (position - window.start());
        position++;
        return window.data()[index] & 0xFF;
    }

    /**
     * Reads many bytes at one time.
     *
     * <p>Without this operation {@link InputStream} reads one byte for each call of
     * {@link #read()}. A player moves gigabytes through a buffer of 64 KB, thus that costs one
     * call for each byte. This operation copies what the window holds, and it gives less than
     * {@code length} bytes when the window ends before that: a caller of a stream reads in a
     * loop.</p>
     */
    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (length == 0) {
            return 0;
        }
        if (!load()) {
            return -1;
        }
        int index = (int) (position - window.start());
        int count = (int) Math.min(length, window.end() - position);
        System.arraycopy(window.data(), index, buffer, offset, count);
        position += count;
        return count;
    }

    /**
     * Gives the stream a window that holds the position.
     *
     * @return false when no byte of the file is at the position
     */
    private boolean load() throws IOException {
        if (!file.hasNext(position)) {
            return false;
        }
        if (window != null && window.holds(position)) {
            return true;
        }
        window = source.at(position);
        if (window == null || !window.holds(position)) {
            log.debug("{}: no bytes at position {} of {}", file.filename(), position,
                    file.getSize());
            window = null;
            return false;
        }
        return true;
    }

    /**
     * Moves the cursor.
     *
     * <p>A move inside the window that the stream holds keeps that window and tells the source
     * nothing. Tika marks the stream, reads a few bytes and moves back; without this, that move
     * downloads again the bytes that are already in the memory.</p>
     */
    public void seek(long newPosition) {
        if (window != null && window.holds(newPosition)) {
            position = newPosition;
            return;
        }
        position = newPosition;
        window = null;
        source.moveTo(newPosition);
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
