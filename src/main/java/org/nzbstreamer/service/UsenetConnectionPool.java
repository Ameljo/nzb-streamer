package org.nzbstreamer.service;

import jakarta.annotation.PreDestroy;
import org.apache.commons.net.nntp.NNTPClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Keeps the connections to the news server and gives them to the callers.
 *
 * <p>A new connection needs near to 200 ms: the TCP operation, the authentication and the command
 * GROUP. A segment needs near to 300 ms for the transfer. Thus a connection for each segment
 * makes the download 40 % slower. This pool gives a connection that is ready.</p>
 *
 * <p>A connection also keeps its newsgroup. The pool sends the command GROUP only when the group
 * changes.</p>
 *
 * <p>The pool is a bean, thus all the streams use the same connections. The property
 * {@code usenet.pool-size} gives the number of connections. The provider of the news
 * server gives a maximum, usually between 8 and 50.</p>
 */
@Component
public class UsenetConnectionPool {

    private static final Logger log = LogManager.getLogger(UsenetConnectionPool.class);

    private final NNTPClientFactory clientFactory;
    private final int size;

    /** One permit for each connection. A caller waits here when all the connections are in use. */
    private final Semaphore permits;

    private final BlockingQueue<PooledClient> free = new LinkedBlockingQueue<>();

    /** Reads the rest of the answers that a caller of {@link #releaseAfterDrain} did not read. */
    private final ExecutorService drains = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "segment-drain");
        thread.setDaemon(true);
        return thread;
    });

    public UsenetConnectionPool(NNTPClientFactory clientFactory,
                                @Value("${usenet.pool-size:40}") int size) {
        this.clientFactory = clientFactory;
        this.size = size;
        this.permits = new Semaphore(size);
        log.info("connection pool of {} connections", size);
    }

    /** A connection and the newsgroup that it selected. */
    public static final class PooledClient {

        private final NNTPClient client;
        private String group;

        private PooledClient(NNTPClient client) {
            this.client = client;
        }

        public NNTPClient client() {
            return client;
        }

        public String group() {
            return group;
        }

        public void group(String group) {
            this.group = group;
        }
    }

    /**
     * Gives a connection. The caller must give it back with {@link #release(PooledClient)} or with
     * {@link #discard(PooledClient)}.
     */
    public PooledClient borrow() throws IOException, InterruptedException {
        long startedAt = System.nanoTime();
        permits.acquire();
        long waitedMs = (System.nanoTime() - startedAt) / 1_000_000;
        if (waitedMs > 0) {
            log.debug("waited {} ms for a connection, {} free of {}", waitedMs,
                    permits.availablePermits(), size);
        }
        try {
            PooledClient pooled = free.poll();
            if (pooled != null && !pooled.client().isConnected()) {
                log.debug("a connection of the pool is not connected, thus the pool makes a new one");
                closeQuietly(pooled);
                pooled = null;
            }
            if (pooled == null) {
                pooled = new PooledClient(clientFactory.createClient());
            }
            return pooled;
        } catch (IOException | RuntimeException e) {
            permits.release();
            throw e;
        }
    }

    /** Gives the connection back to the pool. */
    public void release(PooledClient pooled) {
        free.offer(pooled);
        permits.release();
    }

    /**
     * Reads the rest of an answer on another thread, then takes the connection back.
     *
     * <p>A caller that needs only the first bytes of an article uses this. A close operation on
     * the reader reads the bytes that stay, and the connection is then at the end of the answer
     * and good for the next command. That read happens here, thus the caller does not wait for
     * it.</p>
     *
     */
    public void releaseAfterDrain(PooledClient pooled, Reader reader) {
        long startedAt = System.nanoTime();
        drains.submit(() -> {
            try {
                reader.close();
                release(pooled);
                log.debug("the rest of an answer arrived in {} ms",
                        (System.nanoTime() - startedAt) / 1_000_000);
            } catch (IOException e) {
                log.debug("cannot read the rest of an answer: {}", e.getMessage());
                discard(pooled);
            }
        });
    }

    /** Removes a connection that has an error. The next caller makes a new one. */
    public void discard(PooledClient pooled) {
        closeQuietly(pooled);
        permits.release();
    }

    @PreDestroy
    public void close() {
        PooledClient pooled;
        while ((pooled = free.poll()) != null) {
            closeQuietly(pooled);
        }
        log.info("connection pool closed");
    }

    private void closeQuietly(PooledClient pooled) {
        try {
            pooled.client().disconnect();
        } catch (IOException e) {
            log.debug("cannot close a connection: {}", e.getMessage());
        }
    }
}
