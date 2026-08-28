package org.nzbstreamer.streams;

import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.service.SegmentFetcher;

/**
 * Makes the streams that read a {@link VirtualFile}.
 *
 * <p>A {@link VirtualFile} is a plain value: it holds the chunks and the sizes, and it knows
 * nothing of the news server. This class holds the {@link SegmentFetcher} and gives a stream for
 * a file, thus the file gives no stream of its own.</p>
 */
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
    public VirtualFileStream open(VirtualFile file) {
        return new VirtualFileStream(file, new PrefetchingSource(file, fetcher));
    }

    public VirtualFileStream openStream(VirtualFile file) {
        return new VirtualFileStream(file, new StreamingSource(file, fetcher));
    }

    /** A stream that reads and downloads in chunks of the given size, instead of the default. */
    public VirtualFileStream openStream(VirtualFile file, int bufferSize) {
        return new VirtualFileStream(file,
                new StreamingSource(file, fetcher, StreamingSource.MAX_RETRIES, bufferSize));
    }

    /**
     * A stream that downloads a segment when a read needs it, and nothing before that.
     *
     * <p>A parser that reads a few bytes and moves the cursor over the rest uses this one: it
     * downloads the segments that hold the bytes that the parser reads, and no others.</p>
     */
    public VirtualFileStream openRange(VirtualFile file) {
        return new VirtualFileStream(file, new OnDemandSource(file, fetcher));
    }

    public VirtualFileStream openDynamic(VirtualFile file) {
        return new VirtualFileStream(file, new DynamicSource(file, fetcher));
    }
}
