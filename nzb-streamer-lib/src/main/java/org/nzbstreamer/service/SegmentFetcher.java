package org.nzbstreamer.service;

import org.nzbstreamer.exceptions.UsenetException;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;

/**
 * Gives the bytes of a segment, decoded from its yEnc article.
 *
 * <p>{@link PooledSegmentFetcher} is the real implementation, downloading over NNTP through a
 * {@link UsenetConnectionPool}. Every method has a default that throws
 * {@link UnsupportedOperationException}, so a fake used in a test only needs to override the
 * one method it actually exercises.</p>
 */
public interface SegmentFetcher {

    /** Gives the bytes of one segment. */
    default byte[] fetch(String messageId, String group)
            throws IOException, InterruptedException, UsenetException {
        throw new UnsupportedOperationException();
    }

    /**
     * Gives just the bytes of one segment that the file uses, trimmed of the archive bytes
     * around them.
     *
     * <p>Built on {@link #fetch(String, String)}, through {@code this}: an implementation that
     * caches that method (like {@code CachingSegmentFetcher}) is cached here too, for free, and
     * a caller of this method never needs to know the segment's raw bytes or slice them itself.</p>
     */
    default byte[] fetch(String messageId, String group, int skip, int trim)
            throws IOException, InterruptedException, UsenetException {
        byte[] full = fetch(messageId, group);
        int from = Math.min(skip, full.length);
        int to = Math.min(skip + trim, full.length);
        return from < to ? Arrays.copyOfRange(full, from, to) : new byte[0];
    }

    /**
     * Gives the bytes of one segment, one decoded window at a time, through the buffer.
     *
     * @return the whole segment, not just the {@code [skip, skip+trim)} window given to the
     *         buffer -- so a caller can cache the complete segment, reusable for any other window
     *         of it
     */
    default byte[] fetch(String messageId, String group, BlockingQueue<byte[]> buffer,
                        int bufferSize, int skip, int trim)
            throws IOException, InterruptedException, UsenetException {
        throw new UnsupportedOperationException();
    }

    /** Gives the first bytes of one segment, at most {@code maxBytes} of them. */
    default byte[] fetchPrefix(String messageId, String group, int maxBytes)
            throws IOException, InterruptedException, UsenetException {
        throw new UnsupportedOperationException();
    }
}
