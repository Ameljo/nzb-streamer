package org.nzbstreamer.service;

import org.nzbstreamer.exceptions.UsenetException;

import java.io.IOException;
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

    /** Gives the bytes of one segment, one decoded window at a time, through the buffer. */
    default void fetch(String messageId, String group, BlockingQueue<byte[]> buffer,
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
