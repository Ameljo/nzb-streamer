package org.nzbstreamer.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.decoder.MultiPartDecoder;
import org.nzbstreamer.exceptions.ArticleUnavaliableException;
import org.nzbstreamer.exceptions.UsenetException;
import org.nzbstreamer.utils.NzbUtils;

import java.io.IOException;
import java.io.Reader;
import java.util.concurrent.BlockingQueue;

/**
 * Downloads one segment over NNTP and gives its bytes after the decode operation.
 *
 * <p>The fetcher uses a connection of {@link UsenetConnectionPool}. Thus it does not make a new
 * connection for each segment.</p>
 */
public class PooledSegmentFetcher implements SegmentFetcher {

    private static final Logger log = LogManager.getLogger(PooledSegmentFetcher.class);

    private final UsenetConnectionPool pool;

    public PooledSegmentFetcher(UsenetConnectionPool pool) {
        this.pool = pool;
    }

    /**
     * Gives the bytes of one segment.
     *
     * <p>This operation makes one attempt. The evictor of the pool closes the connections that
     * the server closed, and {@code DownloadSegmentsWorker} makes the attempts that stay.</p>
     *
     * @throws org.nzbstreamer.exceptions.ArticleUnavaliableException if the segment is not on the
     *         server
     */
    @Override
    public byte[] fetch(String messageId, String group)
            throws IOException, InterruptedException, UsenetException {
        long startedAt = System.nanoTime();
        PooledClient pooled = pool.borrow(group);
        long transferStart = System.nanoTime();
        byte[] bytes;

        // retrieveArticle is the name of the command of NNTP. A segment is one article.
        // The reader must read all the segment: its close operation reads the bytes that stay,
        // thus the connection is at the end of the answer and good for the next command.
        try (Reader body = pooled.retrieveArticle(NzbUtils.normalizeMessageId(messageId))) {
            bytes = new MultiPartDecoder().decode(body);
        } catch (Throwable t) {
            // The read stopped in the middle of the answer, or the connection has an error. The
            // catch holds the close operation as well, thus a read of the rest that fails also
            // arrives here.
            pool.discard(pooled);
            throw t;
        }
        pool.release(pooled);

//        log.debug("segment {}: {} bytes in {} ms = transfer {} ms", messageId,
//                bytes.length, (System.nanoTime() - startedAt) / 1_000_000,
//                (System.nanoTime() - transferStart) / 1_000_000);
        return bytes;
    }

    @Override
    public byte[] fetch(String messageId, String group, BlockingQueue<byte[]> buffer, int bufferSize, int skip, int trim) throws IOException, UsenetException, InterruptedException {
        long startedAt = System.nanoTime();
        PooledClient pooled = pool.borrow(group);
        long transferStart = System.nanoTime();
        byte[] bytes;

        // retrieveArticle is the name of the command of NNTP. A segment is one article.
        // The reader must read all the segment: its close operation reads the bytes that stay,
        // thus the connection is at the end of the answer and good for the next command.
        try (Reader body = pooled.retrieveArticle(NzbUtils.normalizeMessageId(messageId))) {
            bytes = new MultiPartDecoder().decode(body, buffer, bufferSize, skip, trim);
        } catch (Throwable t) {
            // The read stopped in the middle of the answer, or the connection has an error. The
            // catch holds the close operation as well, thus a read of the rest that fails also
            // arrives here.
            pool.discard(pooled);
            throw t;
        }
        pool.release(pooled);

//        log.debug("segment {}: {} bytes in {} ms = transfer {} ms", messageId,
//                bytes.length, (System.nanoTime() - startedAt) / 1_000_000,
//                (System.nanoTime() - transferStart) / 1_000_000);
        return bytes;
    }

    /**
     * Gives the first bytes of one segment, at most {@code maxBytes} of them.
     *
     * <p>A parser reads the first bytes of a segment and moves the cursor over the rest. This
     * operation gives it those bytes as soon as they arrive.</p>
     *
     * <p>A connection that gave a part of an article cannot take another command: the answer of
     * the server did not arrive at its end. Thus this operation closes it and the pool opens
     * another one. That costs one connection, and it saves the megabytes of the article that
     * nobody reads.</p>
     */
    @Override
    public byte[] fetchPrefix(String messageId, String group, int maxBytes)
            throws IOException, InterruptedException, UsenetException {
        long startedAt = System.nanoTime();
        PooledClient pooled = pool.borrow(group);
        byte[] bytes;
        boolean readToTheEnd;

        try {
            Reader reader = pooled.retrieveArticle(NzbUtils.normalizeMessageId(messageId));
            bytes = new MultiPartDecoder().decodePrefix(reader, maxBytes);
            // The segment is smaller than the limit, thus the reader arrived at the end of the
            // answer and the connection stays in the pool.
            readToTheEnd = bytes.length < maxBytes;
        } catch (Throwable t) {
            pool.discard(pooled);
            throw t;
        }

        if (readToTheEnd) {
            pool.release(pooled);
        } else {
            pool.discard(pooled);
        }

        log.debug("segment {}: {} bytes of the start in {} ms{}", messageId, bytes.length,
                (System.nanoTime() - startedAt) / 1_000_000, readToTheEnd ? "" : " (prefix)");
        return bytes;
    }

}
