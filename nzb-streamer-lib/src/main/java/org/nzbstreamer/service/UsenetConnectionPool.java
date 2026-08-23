package org.nzbstreamer.service;

import org.apache.commons.pool2.impl.AbandonedConfig;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.client.UsenetServerConfig;
import org.nzbstreamer.exceptions.PoolExhaustedException;
import org.nzbstreamer.exceptions.UsenetException;

import java.time.Duration;
import java.util.NoSuchElementException;

/**
 * Gives the connections of the news server to the callers.
 *
 * <p>A new connection needs near to 200 ms: the TCP operation, the authentication and the command
 * GROUP. A segment needs near to 300 ms for the transfer. Thus a connection for each segment
 * makes the download 40 % slower. This pool gives a connection that is ready.</p>
 *
 * <p>The pool itself is a {@link GenericObjectPool} of Apache Commons Pool. This class holds it
 * and gives the exceptions of this application: a caller sees {@link UsenetException} and not the
 * {@code Exception} of the library. {@link #create(UsenetServerConfig)} makes the pool.</p>
 */
public class UsenetConnectionPool implements AutoCloseable {

    private static final Logger log = LogManager.getLogger(UsenetConnectionPool.class);

    private static final long SLOW_BORROW_MS = 1_000;

    private final GenericObjectPool<PooledClient> pool;

    public UsenetConnectionPool(GenericObjectPool<PooledClient> pool) {
        this.pool = pool;
    }

    /**
     * Makes a pool of connections for one news server.
     *
     * <p>The property {@code poolSize} gives the number of connections. The provider of the news
     * server gives a maximum, usually between 8 and 50.</p>
     *
     * <p>The pool opens no connection at the start: it opens one when a caller asks and the pool
     * has none free, and it stops at {@code poolSize}. Thus a run that reads one file uses the
     * few connections that it needs.</p>
     */
    public static UsenetConnectionPool create(UsenetServerConfig config) {
        NNTPClientFactory clientFactory = new NNTPClientFactory(config);
        int size = config.poolSize();

        GenericObjectPoolConfig<PooledClient> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(size);
        // A connection that is free stays in the pool. Only the evictor closes it.
        poolConfig.setMaxIdle(size);
        // No connection at the start. The pool opens them when the callers ask.
        poolConfig.setMinIdle(0);
        poolConfig.setBlockWhenExhausted(true);
        poolConfig.setMaxWait(Duration.ofSeconds(config.poolWaitSeconds()));
        // The last connection that came back goes out first, thus the connections stay warm.
        poolConfig.setLifo(true);

        // The evictor asks the connections that wait whether they answer, and it closes the ones
        // that the server closed. Thus a caller does not wait for that question.
        poolConfig.setTestOnBorrow(false);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofMinutes(1));
        poolConfig.setMinEvictableIdleDuration(Duration.ofMinutes(config.poolIdleMinutes()));
        poolConfig.setNumTestsPerEvictionRun(size);

        GenericObjectPool<PooledClient> pool =
                new GenericObjectPool<>(new PooledClientFactory(clientFactory), poolConfig);

        // A caller that takes a connection and gives back none holds it for ever. This closes a
        // connection that a caller holds for a long time, thus one error does not empty the pool.
        AbandonedConfig abandoned = new AbandonedConfig();
        abandoned.setRemoveAbandonedOnMaintenance(true);
        abandoned.setRemoveAbandonedTimeout(Duration.ofMinutes(10));
        abandoned.setLogAbandoned(true);
        pool.setAbandonedConfig(abandoned);

        log.info("connection pool of {} connections", size);
        return new UsenetConnectionPool(pool);
    }

    /** Closes every connection of the pool. A caller that owns the pool closes it once, at the end. */
    @Override
    public void close() {
        pool.close();
    }

    /**
     * Gives a connection that selected the newsgroup.
     *
     * <p>The caller gives it back with {@link #release(PooledClient)} when it read the answer to
     * the end, and with {@link #discard(PooledClient)} when it did not.</p>
     */
    public PooledClient borrow(String group) throws UsenetException, InterruptedException {
        PooledClient pooled = borrow();
        try {
            pooled.selectNewsgroup(group);
            return pooled;
        } catch (UsenetException | RuntimeException e) {
            discard(pooled);
            throw e;
        }
    }

    /** Gives the connection back to the pool. */
    public void release(PooledClient pooled) {
        try {
            pool.returnObject(pooled);
        } catch (IllegalStateException e) {
            // The pool knows the connections that the callers hold, thus it finds this.
            log.error("a connection came back to the pool two times", e);
        }
    }

    /**
     * Closes a connection that the caller cannot use again, and the pool opens another one when a
     * caller asks for it.
     *
     * <p>A caller that read the first bytes of an article and stopped there uses this: the answer
     * of the server did not arrive at its end, thus the connection cannot take another
     * command.</p>
     */
    public void discard(PooledClient pooled) {
        try {
            pool.invalidateObject(pooled);
        } catch (Exception e) {
            log.debug("cannot close a connection: {}", e.getMessage());
        }
    }

    /** Takes a connection and gives the exceptions of this application, not those of the pool. */
    private PooledClient borrow() throws UsenetException, InterruptedException {
        long startedAt = System.nanoTime();
        try {
            PooledClient pooled = pool.borrowObject();
            logBorrowTime(startedAt);
            return pooled;
        } catch (UsenetException e) {
            // The factory threw it and the pool gives it back as it is.
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (NoSuchElementException e) {
            long waitedMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.warn("waited {} ms for a connection and got none, {} active of {} — the pool is"
                    + " exhausted", waitedMs, pool.getNumActive(), pool.getMaxTotal());
            throw new PoolExhaustedException(pool.getMaxTotal(), e);
        } catch (Exception e) {
            throw new UsenetException("Cannot take a connection from the pool", e);
        }
    }

    private void logBorrowTime(long startedAt) {
        long waitedMs = (System.nanoTime() - startedAt) / 1_000_000;
        if (waitedMs > SLOW_BORROW_MS) {
            log.warn("waited {} ms for a connection, {} active of {} — the pool is the"
                    + " bottleneck", waitedMs, pool.getNumActive(), pool.getMaxTotal());
        } else if (waitedMs > 0) {
            log.debug("waited {} ms for a connection, {} active of {}", waitedMs,
                    pool.getNumActive(), pool.getMaxTotal());
        }
    }
}
