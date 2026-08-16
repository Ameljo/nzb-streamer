package org.nzbstreamer.streams.proto;

import org.nzbstreamer.exceptions.UsenetException;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.service.SegmentFetcher;

import java.io.IOException;

/**
 * Downloads a segment when a read needs its bytes, and nothing before that.
 *
 * <p>A parser that reads the first bytes of a file and moves the cursor over the rest thus
 * downloads only the segments that hold the bytes it reads. This is the source of the header
 * scan.</p>
 *
 * <p>A segment can hold megabytes. The source takes {@value #PREFIX_BYTES} bytes of it and stops
 * the transfer, and it takes all the segment only when a read needs a byte after them.</p>
 *
 * <p>PROTOTYPE. Nothing uses this package yet.</p>
 */
public class OnDemandSource implements SegmentSource {

    /** The number of bytes of the first read of a segment. */
    private static final int PREFIX_BYTES = 64 * 1024;

    private final VirtualFile file;
    private final SegmentFetcher fetcher;

    private byte[] segment;
    /** The position of the file for the first byte of {@link #segment}. */
    private long segmentStart;
    /** The position of the file after the last byte that the file uses in the segment. */
    private long segmentEnd;

    public OnDemandSource(VirtualFile file, SegmentFetcher fetcher) {
        this.file = file;
        this.fetcher = fetcher;
    }

    @Override
    public Window at(long position) throws IOException {
        if (!file.hasNext(position)) {
            return null;
        }

        // The source holds the segment of the position, but only its first bytes. The read needs a
        // byte after them, thus this operation takes all the segment.
        boolean needsAllOfIt = segment != null && position >= segmentStart && position < segmentEnd;

        VirtualFile.Location location = file.locate(position);
        long start = position - location.byteInSegment();
        try {
            segment = needsAllOfIt
                    ? fetcher.fetch(location.segment().getValue(), location.group())
                    : fetcher.fetchPrefix(location.segment().getValue(), location.group(),
                            location.byteInSegment() + PREFIX_BYTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while it downloads a segment "
                    + location.segment().getValue() + " of group " + location.group()
                    + " at position " + position, e);
        } catch (UsenetException e) {
            throw new IOException("Failed to download segment " + location.segment().getValue()
                    + " of group " + location.group() + " at position " + position, e);
        }

        segmentStart = start;
        segmentEnd = position + location.bytesLeftInSegment();
        // The array can hold fewer bytes than the segment when the transfer stopped at the prefix,
        // and more than the file uses when the archive holds bytes after it.
        int length = (int) Math.min(segment.length, segmentEnd - segmentStart);
        return length <= 0 ? null : new Window(segment, segmentStart, length);
    }

    @Override
    public void moveTo(long position) {
        // Nothing is in flight. The next call of at() downloads what the position needs.
    }

    @Override
    public void close() {
        segment = null;
    }
}
