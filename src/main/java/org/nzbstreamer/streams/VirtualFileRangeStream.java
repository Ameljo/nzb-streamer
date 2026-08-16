package org.nzbstreamer.streams;

import org.nzbstreamer.exceptions.UsenetException;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.service.SegmentFetcher;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads a {@link VirtualFile} without a worker. It downloads a segment when a read needs the bytes
 * of that segment, and it downloads nothing before that. A parser that reads a few bytes and moves
 * the cursor over the rest thus downloads only the segments that hold the bytes it reads.
 */
public class VirtualFileRangeStream extends InputStream {

    private final VirtualFile file;
    private final SegmentFetcher fetcher;

    private long position;
    private long markPosition;

    /** The location of the position. A move of the cursor makes it stale. */
    private VirtualFile.Location location;

    /**
     * The bytes of the first read of a segment.
     *
     * <p>A parser reads the first bytes of a file and moves the cursor over the rest. A segment
     * can hold megabytes, thus the stream takes this number of bytes and stops the transfer. It
     * takes all the segment only when a read needs a byte after them.</p>
     */
    private static final int PREFIX_BYTES = 64 * 1024;

    private byte[] segment;
    /** The position of the file for the first byte of {@link #segment}. */
    private long segmentStart;
    /** The position of the file after the last byte that the file uses in {@link #segment}. */
    private long segmentEnd;

    public VirtualFileRangeStream(VirtualFile file, SegmentFetcher fetcher) {
        this.file = file;
        this.fetcher = fetcher;
    }

    @Override
    public int read() throws IOException {
        if (!load()) {
            return -1;
        }
        int index = (int) (position - segmentStart);
        position++;
        return segment[index] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (!load()) {
            return -1;
        }
        int index = (int) (position - segmentStart);
        int count = (int) Math.min(length, readableEnd() - position);
        System.arraycopy(segment, index, buffer, offset, count);
        position += count;
        return count;
    }

    /** The position of the file after the last byte that the stream holds now. */
    private long readableEnd() {
        return Math.min(segmentEnd, segmentStart + segment.length);
    }

    /** Gives the segment of the position, from the memory or from the news server. */
    private boolean load() throws IOException {
        if (!file.hasNext(position)) {
            return false;
        }
        if (segment != null && position >= segmentStart && position < readableEnd()) {
            return true;
        }
        // The stream holds the segment of the position, but only its first bytes. The read needs
        // a byte after them, thus this operation takes all the segment.
        boolean needsAllOfIt =
                segment != null && position >= segmentStart && position < segmentEnd;

        location = file.locate(position);
        long start = position - location.byteInSegment();
        try {
            segment = needsAllOfIt
                    ? fetcher.fetch(location.segment().getValue(), location.group())
                    : fetcher.fetchPrefix(location.segment().getValue(), location.group(),
                            location.byteInSegment() + PREFIX_BYTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while it downloads a segment " + location.segment().getValue()
                    + " of group " + location.group() + " at position " + position, e);
        } catch (UsenetException e) {
            throw new IOException("Failed to download segment " + location.segment().getValue()
                    + " of group " + location.group() + " at position " + position, e);
        }
        segmentStart = start;
        segmentEnd = position + location.bytesLeftInSegment();
        return position < readableEnd();
    }

    /** Moves the cursor. It downloads nothing: the next read does that. */
    public void seek(long newPosition) {
        position = newPosition;
        location = null;
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
}
