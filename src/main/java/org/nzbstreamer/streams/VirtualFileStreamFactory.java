package org.nzbstreamer.streams;

import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.service.SegmentFetcher;
import org.springframework.stereotype.Component;

/**
 * Makes the streams that read a {@link VirtualFile}.
 *
 * <p>A {@link VirtualFile} is a row of the database: it holds the chunks and the sizes, and it
 * knows nothing of the news server. This class holds the {@link SegmentFetcher} and gives a
 * stream for a file, thus the entity gives no stream of its own.</p>
 */
@Component
public class VirtualFileStreamFactory {

    private final SegmentFetcher fetcher;

    public VirtualFileStreamFactory(SegmentFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * A stream for reading a file in sequence.
     *
     * <p>A worker downloads the segments that come after the position, thus a player that reads
     * the file from one end to the other does not wait for each segment.</p>
     */
    public VirtualFileInputStream open(VirtualFile file) {
        return new VirtualFileInputStream(file, fetcher);
    }

    /**
     * A stream that downloads a segment when a read needs it, and nothing before that.
     *
     * <p>A parser that reads a few bytes and moves the cursor over the rest uses this one: it
     * downloads the segments that hold the bytes that the parser reads, and no others.</p>
     */
    public VirtualFileRangeStream openRange(VirtualFile file) {
        return new VirtualFileRangeStream(file, fetcher);
    }
}
