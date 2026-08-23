package org.nzbstreamer.config;

import org.nzbstreamer.client.NzbStreamerClient;
import org.nzbstreamer.client.UsenetServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the library's {@link NzbStreamerClient} into a Spring bean, reading its server address,
 * credentials, and pool tuning from {@code usenet.*} application properties. This is the only
 * place in the app that knows those properties come from Spring {@code @Value} -- the library
 * itself only ever sees a plain {@link UsenetServerConfig}.
 */
@Configuration
public class UsenetClientConfig {

    @Bean(destroyMethod = "close")
    public NzbStreamerClient nzbStreamerClient(
            @Value("${usenet.server}") String server,
            @Value("${usenet.port}") int port,
            @Value("${usenet.use-tls:false}") boolean useTls,
            @Value("${usenet.username}") String username,
            @Value("${usenet.password}") String password,
            @Value("${usenet.pool-size:40}") int poolSize,
            @Value("${usenet.pool-wait-seconds:30}") int poolWaitSeconds,
            @Value("${usenet.pool-idle-minutes:5}") int poolIdleMinutes,
            @Value("${nntp.connection-timeout:10000}") int connectTimeoutMs,
            @Value("${nntp.read-timeout:10000}") int readTimeoutMs) {
        UsenetServerConfig config = UsenetServerConfig.builder()
                .host(server)
                .port(port)
                .tls(useTls)
                .username(username)
                .password(password)
                .poolSize(poolSize)
                .poolWaitSeconds(poolWaitSeconds)
                .poolIdleMinutes(poolIdleMinutes)
                .connectTimeoutMs(connectTimeoutMs)
                .readTimeoutMs(readTimeoutMs)
                .build();
        return NzbStreamerClient.forServer(config);
    }
}
