package org.nzbstreamer.service;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds decoded segment bytes, keyed by the caller, up to a total byte budget.
 *
 * <p>The oldest entry is evicted first once the budget is exceeded, and an access counts as a
 * use: {@link #get} marks the entry most-recently-used.</p>
 */
public class SegmentCache {

    private final long maxBytes;
    private long currentBytes = 0;

    private final LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return false; // eviction below is size-based, not count-based
        }
    };

    public SegmentCache(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public synchronized byte[] get(String key) {
        return entries.get(key);
    }

    public synchronized void put(String key, byte[] bytes) {
        if (entries.containsKey(key)) {
            return;
        }
        entries.put(key, bytes);
        currentBytes += bytes.length;
        Iterator<Map.Entry<String, byte[]>> it = entries.entrySet().iterator();
        while (currentBytes > maxBytes && it.hasNext()) {
            currentBytes -= it.next().getValue().length;
            it.remove();
        }
    }
}
