package org.nzbstreamer.service;

import jakarta.annotation.PreDestroy;
import org.apache.commons.net.nntp.NNTPClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
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

    /**
     * The permits of the work in the background, for example a scan of an image.
     *
     * <p>That work takes a permit here and a permit of {@link #permits}. Thus the connections that
     * stay are always available for a read operation of a player.</p>
     */
    private final Semaphore backgroundPermits;

    private final BlockingQueue<PooledClient> free = new LinkedBlockingQueue<>();

    public UsenetConnectionPool(NNTPClientFactory clientFactory,
                                @Value("${usenet.pool-size:40}") int size,
                                @Value("${usenet.background-share:0.7}") double backgroundShare) {
        this.clientFactory = clientFactory;
        this.size = size;
        this.permits = new Semaphore(size);
        int background = Math.max(1, (int) Math.round(size * backgroundShare));
        this.backgroundPermits = new Semaphore(background);
        log.info("connection pool of {} connections, {} for the work in the background", size,
                background);
    }

    /** A connection and the newsgroup that it selected. */
    public static final class PooledClient {

        private final NNTPClient client;
        private String group;
        private boolean background;

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
        return borrow(false);
    }

    /**
     * Gives a connection. Work in the background waits when it uses its part of the pool.
     *
     * @param background true for work that must not stop a read operation of a player
     */
    public PooledClient borrow(boolean background) throws IOException, InterruptedException {
        if (background) {
            backgroundPermits.acquire();
        }
        try {
            return borrowConnection(background);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (background) {
                backgroundPermits.release();
            }
            throw e;
        }
    }

    private PooledClient borrowConnection(boolean background) throws IOException, InterruptedException {
        permits.acquire();
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
            pooled.background = background;
            return pooled;
        } catch (IOException | RuntimeException e) {
            permits.release();
            throw e;
        }
    }

    /** Gives the connection back to the pool. */
    public void release(PooledClient pooled) {
        boolean background = pooled.background;
        free.offer(pooled);
        permits.release();
        if (background) {
            backgroundPermits.release();
        }
    }

    /** Removes a connection that has an error. The next caller makes a new one. */
    public void discard(PooledClient pooled) {
        closeQuietly(pooled);
        permits.release();
        if (pooled.background) {
            backgroundPermits.release();
        }
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
