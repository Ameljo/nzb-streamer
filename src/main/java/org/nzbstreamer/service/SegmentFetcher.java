package org.nzbstreamer.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.decoder.MultiPartDecoder;
import org.nzbstreamer.utils.NzbUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    public byte[] fetch(String messageId, String group)
            throws IOException, InterruptedException {
        long startedAt = System.nanoTime();
        UsenetConnectionPool.PooledClient pooled = pool.borrow();
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

    /**
     * Gives the first bytes of one segment, at most {@code maxBytes} of them.
     *
     * <p>A parser reads the first bytes of a segment and moves the cursor over the rest. This
     * operation gives it those bytes as soon as they arrive, and a thread of the drain reads the
     * rest of the article. Thus the caller waits for the bytes it reads, and not for a segment
     * that holds megabytes.</p>
     */
    public byte[] fetchPrefix(String messageId, String group, int maxBytes)
            throws IOException, InterruptedException {
        long startedAt = System.nanoTime();
        UsenetConnectionPool.PooledClient pooled = pool.borrow();
        boolean handedOver = false;
        boolean healthy = false;

        try {
            if (!group.equals(pooled.group())) {
                if (!pooled.client().selectNewsgroup(group)) {
                    throw new IOException("Failed to select group: " + group);
                }
                pooled.group(group);
            }

            Reader reader = pooled.client().retrieveArticle(NzbUtils.normalizeMessageId(messageId));
            if (reader == null) {
                throw new IOException("Segment not found: " + messageId + " (Reply: "
                        + pooled.client().getReplyCode() + " - " + pooled.client().getReplyString() + ")");
            }

            byte[] bytes = new MultiPartDecoder().decodePrefix(reader, maxBytes);
            if (bytes.length >= maxBytes) {
                pool.releaseAfterDrain(pooled, reader);
                handedOver = true;
            } else {
                // The segment is smaller than the limit, thus the reader is at the end of the
                // answer and the connection stays in the pool.
                healthy = true;
            }

            log.debug("segment {}: {} bytes of the start in {} ms{}", messageId, bytes.length,
                    (System.nanoTime() - startedAt) / 1_000_000, handedOver ? " (prefix)" : "");
            return bytes;

        } finally {
            if (!handedOver) {
                if (healthy) {
                    pool.release(pooled);
                } else {
                    pool.discard(pooled);
                }
            }
        }
    }

}
