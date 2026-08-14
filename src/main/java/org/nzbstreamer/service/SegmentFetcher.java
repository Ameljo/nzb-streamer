package org.nzbstreamer.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.decoder.MultiPartDecoder;
import org.nzbstreamer.utils.NzbUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;

/**
 * Downloads one segment and gives its bytes after the decode operation.
 *
 * <p>The fetcher uses a connection of {@link UsenetConnectionPool}. Thus it does not make a new
 * connection for each segment.</p>
 */
@Component
public class SegmentFetcher {

    private static final Logger log = LogManager.getLogger(SegmentFetcher.class);

    private final UsenetConnectionPool pool;

    public SegmentFetcher(UsenetConnectionPool pool) {
        this.pool = pool;
    }

    /**
     * Gives the bytes of one segment.
     *
     * @throws IOException if the segment is not on the server, or if the connection has an error
     */
    public byte[] fetch(String messageId, String group) throws IOException, InterruptedException {
        return fetch(messageId, group, false);
    }

    /**
     * Gives the bytes of one segment.
     *
     * @param background true for work that must not stop a read operation of a player
     * @throws IOException if the segment is not on the server, or if the connection has an error
     */
    public byte[] fetch(String messageId, String group, boolean background)
            throws IOException, InterruptedException {
        long startedAt = System.nanoTime();
        UsenetConnectionPool.PooledClient pooled = pool.borrow(background);
        boolean healthy = false;

        try {
            long groupMs = 0;
            if (!group.equals(pooled.group())) {
                long groupStart = System.nanoTime();
                if (!pooled.client().selectNewsgroup(group)) {
                    throw new IOException("Failed to select group: " + group);
                }
                pooled.group(group);
                groupMs = (System.nanoTime() - groupStart) / 1_000_000;
            }

            long transferStart = System.nanoTime();
            // retrieveArticle is the name of the command of NNTP. A segment is one article.
            Reader reader = pooled.client().retrieveArticle(NzbUtils.normalizeMessageId(messageId));
            if (reader == null) {
                throw new IOException("Segment not found: " + messageId + " (Reply: "
                        + pooled.client().getReplyCode() + " - " + pooled.client().getReplyString() + ")");
            }

            byte[] bytes;
            // The reader must read all the segment. The connection stays in the pool, and the next
            // command of that connection needs a stream that is at the end of the last answer.
            try (Reader body = reader) {
                bytes = new MultiPartDecoder().decode(body);
            }
            healthy = true;

            log.debug("segment {}: {} bytes in {} ms = group {} ms + transfer {} ms", messageId,
                    bytes.length, (System.nanoTime() - startedAt) / 1_000_000, groupMs,
                    (System.nanoTime() - transferStart) / 1_000_000);
            return bytes;

        } finally {
            if (healthy) {
                pool.release(pooled);
            } else {
                // The connection is possibly not in a good state. The pool makes a new one.
                pool.discard(pooled);
            }
        }
    }
}
