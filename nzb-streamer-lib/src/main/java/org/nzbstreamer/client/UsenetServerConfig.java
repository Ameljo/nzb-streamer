package org.nzbstreamer.client;

/**
 * The address, credentials, and connection-pool tuning for one news server.
 *
 * <p>Plain configuration, independent of any framework: a host application reads these values
 * from wherever it sources configuration (environment variables, a properties file, a secrets
 * manager, ...) and hands them to {@link #builder()}.</p>
 */
public final class UsenetServerConfig {

    private final String host;
    private final int port;
    private final boolean tls;
    private final String username;
    private final String password;
    private final int poolSize;
    private final int poolWaitSeconds;
    private final int poolIdleMinutes;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    private UsenetServerConfig(Builder builder) {
        this.host = builder.host;
        this.port = builder.port;
        this.tls = builder.tls;
        this.username = builder.username;
        this.password = builder.password;
        this.poolSize = builder.poolSize;
        this.poolWaitSeconds = builder.poolWaitSeconds;
        this.poolIdleMinutes = builder.poolIdleMinutes;
        this.connectTimeoutMs = builder.connectTimeoutMs;
        this.readTimeoutMs = builder.readTimeoutMs;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String host() { return host; }
    public int port() { return port; }
    /** Whether the connection uses implicit TLS (the socket is encrypted from the first byte). */
    public boolean tls() { return tls; }
    public String username() { return username; }
    public String password() { return password; }
    public int poolSize() { return poolSize; }
    public int poolWaitSeconds() { return poolWaitSeconds; }
    public int poolIdleMinutes() { return poolIdleMinutes; }
    public int connectTimeoutMs() { return connectTimeoutMs; }
    public int readTimeoutMs() { return readTimeoutMs; }

    public static final class Builder {
        private String host;
        private int port = 119;
        private boolean tls = false;
        private String username;
        private String password;
        private int poolSize = 40;
        private int poolWaitSeconds = 30;
        private int poolIdleMinutes = 5;
        private int connectTimeoutMs = 10_000;
        private int readTimeoutMs = 10_000;

        private Builder() {
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * Turns on implicit TLS: the socket is encrypted from the very first byte, the mode
         * providers expect on their TLS port (commonly 563). Without this, the connection --
         * including the login and everything downloaded -- travels in plain text. Off by default,
         * since a plain {@code port(119)} connection is not a TLS connection and turning this on
         * without also pointing {@link #port(int)} at the provider's TLS port will fail the
         * handshake.
         */
        public Builder tls(boolean tls) {
            this.tls = tls;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        /** The number of connections that the pool keeps open at most. */
        public Builder poolSize(int poolSize) {
            this.poolSize = poolSize;
            return this;
        }

        /** How long a caller waits for a connection before the pool gives up. */
        public Builder poolWaitSeconds(int poolWaitSeconds) {
            this.poolWaitSeconds = poolWaitSeconds;
            return this;
        }

        /** How long a connection stays idle in the pool before the evictor closes it. */
        public Builder poolIdleMinutes(int poolIdleMinutes) {
            this.poolIdleMinutes = poolIdleMinutes;
            return this;
        }

        public Builder connectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        public Builder readTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
            return this;
        }

        public UsenetServerConfig build() {
            if (host == null || host.isBlank()) {
                throw new IllegalStateException("a UsenetServerConfig needs a host");
            }
            return new UsenetServerConfig(this);
        }
    }
}
