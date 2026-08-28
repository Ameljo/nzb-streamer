package org.nzbstreamer.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.exceptions.UsenetException;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;

/**
 * Wraps a {@link SegmentFetcher} with a shared {@link SegmentCache}, so a segment already
 * downloaded once is not downloaded again for another file, another position, or another worker
 * generation after a seek.
 *
 * <p>Both overloads cache under the same key, {@code messageId} alone, and always the whole raw
 * segment -- never a trimmed window of it. A window depends on where the caller's read started
 * ({@link org.nzbstreamer.model.VirtualFile.Location#byteInSegment()} is only ever non-zero for a
 * read that starts mid-segment), so two different callers can legitimately want two different
 * windows of the very same segment; caching a window under a shared key would let one caller's
 * slice corrupt another's read. Caching the whole segment sidesteps that: every caller slices its
 * own window locally, from a copy that is always complete.</p>
 */
public class CachingSegmentFetcher implements SegmentFetcher {

    private static final Logger log = LogManager.getLogger(CachingSegmentFetcher.class);

    private final SegmentFetcher delegate;
    private final SegmentCache cache;

    public CachingSegmentFetcher(SegmentFetcher delegate, SegmentCache cache) {
        this.delegate = delegate;
        this.cache = cache;
    }

    @Override
    public byte[] fetch(String messageId, String group)
            throws IOException, InterruptedException, UsenetException {
        byte[] cached = cache.get(messageId);
        if (cached != null) {
            log.debug("segment {}: cache hit", messageId);
            return cached;
        }
        byte[] bytes = delegate.fetch(messageId, group);
        cache.put(messageId, bytes);
        return bytes;
    }

    @Override
    public byte[] fetch(String messageId, String group, BlockingQueue<byte[]> buffer, int bufferSize,
                       int skip, int trim) throws IOException, UsenetException, InterruptedException {
        byte[] cached = cache.get(messageId);
        if (cached != null) {
            log.debug("segment {}: cache hit", messageId);
            int from = Math.min(skip, cached.length);
            int to = Math.min(skip + trim, cached.length);
            buffer.put(from < to ? Arrays.copyOfRange(cached, from, to) : new byte[0]);
            return cached;
        }
        byte[] full = delegate.fetch(messageId, group, buffer, bufferSize, skip, trim);
        cache.put(messageId, full);
        return full;
    }

    @Override
    public byte[] fetchPrefix(String messageId, String group, int maxBytes)
            throws IOException, InterruptedException, UsenetException {
        return delegate.fetchPrefix(messageId, group, maxBytes);
    }
}
