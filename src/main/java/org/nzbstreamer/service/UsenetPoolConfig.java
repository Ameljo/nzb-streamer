package org.nzbstreamer.service;

import org.apache.commons.pool2.impl.AbandonedConfig;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Makes the pool of the connections to the news server.
 *
 * <p>The property {@code usenet.pool-size} gives the number of connections. The provider of the
 * news server gives a maximum, usually between 8 and 50.</p>
 *
 * <p>The pool opens no connection at the start: it opens one when a caller asks and the pool has
 * none free, and it stops at {@code usenet.pool-size}. Thus a run that reads one file uses the
 * few connections that it needs.</p>
 */
@Configuration
public class UsenetPoolConfig {

    private static final Logger log = LogManager.getLogger(UsenetPoolConfig.class);

    @Bean(destroyMethod = "close")
    public GenericObjectPool<PooledClient> usenetConnectionObjectPool(
            NNTPClientFactory clientFactory,
            @Value("${usenet.pool-size:40}") int size,
            @Value("${usenet.pool-wait-seconds:30}") int waitSeconds,
            @Value("${usenet.pool-idle-minutes:5}") int idleMinutes) {

        GenericObjectPoolConfig<PooledClient> config = new GenericObjectPoolConfig<>();
        config.setMaxTotal(size);
        // A connection that is free stays in the pool. Only the evictor closes it.
        config.setMaxIdle(size);
        // No connection at the start. The pool opens them when the callers ask.
        config.setMinIdle(0);
        config.setBlockWhenExhausted(true);
        config.setMaxWait(Duration.ofSeconds(waitSeconds));
        // The last connection that came back goes out first, thus the connections stay warm.
        config.setLifo(true);

        // The evictor asks the connections that wait whether they answer, and it closes the ones
        // that the server closed. Thus a caller does not wait for that question.
        config.setTestOnBorrow(false);
        config.setTestWhileIdle(true);
        config.setTimeBetweenEvictionRuns(Duration.ofMinutes(1));
        config.setMinEvictableIdleDuration(Duration.ofMinutes(idleMinutes));
        config.setNumTestsPerEvictionRun(size);

        GenericObjectPool<PooledClient> pool =
                new GenericObjectPool<>(new PooledClientFactory(clientFactory), config);

        // A caller that takes a connection and gives back none holds it for ever. This closes a
        // connection that a caller holds for a long time, thus one error does not empty the pool.
        AbandonedConfig abandoned = new AbandonedConfig();
        abandoned.setRemoveAbandonedOnMaintenance(true);
        abandoned.setRemoveAbandonedTimeout(Duration.ofMinutes(10));
        abandoned.setLogAbandoned(true);
        pool.setAbandonedConfig(abandoned);

        log.info("connection pool of {} connections", size);
        return pool;
    }
}
