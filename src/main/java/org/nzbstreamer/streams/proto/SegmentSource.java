package org.nzbstreamer.streams.proto;

import java.io.Closeable;
import java.io.IOException;

/**
 * Gives the bytes of a file around a position.
 *
 * <p>This is the part that {@code VirtualFileInputStream} and {@code VirtualFileRangeStream} do
 * differently. Everything else of a stream — the position, the mark, the skip operation and the
 * read operations — is the same in both, and {@link VirtualFileStream} holds it one time.</p>
 *
 * <p>PROTOTYPE. Nothing uses this package yet.</p>
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

    /**
     * Gives a window that holds the position.
     *
     * <p>The source downloads what it needs. It gives null when no byte of the file is at that
     * position.</p>
     */
    Window at(long position) throws IOException;

    /**
     * Says that the reader moved to a position that its window does not hold.
     *
     * <p>A source that downloads in advance stops that work: the bytes that come are the bytes of
     * the position of before, and nobody reads them.</p>
     */
    void moveTo(long position);

    @Override
    void close();
}
