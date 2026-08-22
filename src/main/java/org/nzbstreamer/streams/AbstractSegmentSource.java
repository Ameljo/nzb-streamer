package org.nzbstreamer.streams;

import java.io.IOException;
import java.util.Objects;

/**
 * Holds the window that {@link #fetchWindow(long)} downloaded, and gives the bytes of it one at a
 * time or many at a time.
 *
 * <p>{@link OnDemandSource} and {@link PrefetchingSource} extend this and implement only
 * {@link #fetchWindow(long)}, {@link #onSeek(long)} and {@link #close()} — where the bytes come
 * from. This class is the part that is the same for both: keeping the window across reads, and
 * downloading a new one only when the position leaves it.</p>
 */
public abstract class AbstractSegmentSource implements SegmentSource {

    private long position;

    /** The part of the file that the source holds now, or null. */
    private Window window;

    @Override
    public final int read() throws IOException {
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
     * <p>Without this operation a caller reads one byte for each call of {@link #read()}. This
     * operation copies what the window holds, and it gives fewer than {@code length} bytes when
     * the window ends before that: a caller reads in a loop.</p>
     */
    @Override
    public final int read(byte[] buffer, int offset, int length) throws IOException {
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
     * Moves the cursor.
     *
     * <p>A move inside the window that the source holds keeps that window and calls
     * {@link #onSeek(long)} with nothing: Tika marks the stream, reads a few bytes and moves back,
     * and without this, that move downloads again the bytes that are already in the memory.</p>
     */
    @Override
    public final void seek(long newPosition) {
        if (window != null && window.holds(newPosition)) {
            position = newPosition;
            return;
        }
        position = newPosition;
        window = null;
        onSeek(newPosition);
    }

    /**
     * Gives the source a window that holds the position.
     *
     * @return false when no byte of the file is at the position
     */
    private boolean load() throws IOException {
        if (window != null && window.holds(position)) {
            return true;
        }
        window = fetchWindow(position);
        return window != null && window.holds(position);
    }

    /**
     * Gives a window that holds the position.
     *
     * <p>The implementation downloads what it needs. It gives null when no byte of the file is at
     * that position.</p>
     */
    protected abstract Window fetchWindow(long position) throws IOException;

    /**
     * Says that the reader moved to a position that the window does not hold.
     *
     * <p>An implementation that downloads in advance stops that work: the bytes that come are the
     * bytes of the position of before, and nobody reads them.</p>
     */
    protected abstract void onSeek(long position);
}
