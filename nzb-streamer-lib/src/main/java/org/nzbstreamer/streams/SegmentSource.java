package org.nzbstreamer.streams;

import java.io.Closeable;
import java.io.IOException;

/**
 * Gives the bytes of a file, one after another.
 *
 * <p>This is the part that {@link OnDemandSource} and {@link PrefetchingSource} do differently:
 * where the bytes come from, and how far ahead they download. Everything else — the cursor and
 * what counts as "still in the window I already have" — is up to each of them.</p>
 */
public interface SegmentSource extends Closeable {

    /**
     * A part of a file that is in the memory.
     *
     * <p>{@code data} holds the bytes of the file from the position {@code start}, and
     * {@code length} says how many of them the file uses. A source can give an array that holds
     * more bytes than that: the bytes of an archive that are outside the file stay in the array
     * and outside the window.</p>
     */
    record Window(byte[] data, long start, int length) {

        /** The position of the file after the last byte of this window. */
        public long end() {
            return start + length;
        }

        /** True when the window holds the byte at this position. */
        public boolean holds(long position) {
            return position >= start && position < end();
        }
    }

    /** The next byte of the file, or -1 at the end. */
    int read() throws IOException;

    /**
     * Reads many bytes at one time.
     *
     * <p>Without this operation a caller reads one byte for each call of {@link #read()}. This
     * operation gives fewer than {@code length} bytes when the source has fewer than that ready,
     * and -1 only at the end of the file: a caller reads in a loop.</p>
     */
    int read(byte[] buffer, int offset, int length) throws IOException;

    /** Moves the cursor. */
    void seek(long newPosition);

    @Override
    void close();
}
