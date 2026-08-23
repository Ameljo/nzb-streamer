package org.nzbstreamer.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.commons.net.nntp.NNTPClient;
import org.nzbstreamer.client.UsenetServerConfig;
import org.nzbstreamer.exceptions.UsenetAuthenticationException;
import org.nzbstreamer.exceptions.UsenetConnectionException;
import org.nzbstreamer.exceptions.UsenetException;

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class NNTPClientFactory {

    private static final Logger log = LogManager.getLogger(NNTPClientFactory.class);

    private final UsenetServerConfig config;

    public NNTPClientFactory(UsenetServerConfig config) {
        this.config = config;
    }

    public NNTPClient createClient() throws UsenetException {
        NNTPClient client = new NNTPClient();
        // commons-net has no NNTPSClient (unlike its FTPSClient for FTP); swapping in an SSL
        // socket factory before connect() is the standard way to get implicit TLS -- the socket
        // is encrypted from the first byte, the mode providers expect on their TLS port.
        if (config.tls()) {
            client.setSocketFactory(SSLSocketFactory.getDefault());
        }
        client.setCharset(StandardCharsets.ISO_8859_1);
        client.setConnectTimeout(config.connectTimeoutMs());
        client.setDefaultTimeout(config.readTimeoutMs());

        String server = config.host();
        int port = config.port();
        String username = config.username();
        String password = config.password();

        log.debug("connecting to {}:{} ({})", server, port, config.tls() ? "TLS" : "plain text");
        try {
            client.connect(server, port);
        } catch (IOException e) {
            throw new UsenetConnectionException(server, port, e);
        }
        // A plain-text server cannot complete a TLS handshake, so reaching this line with tls()
        // true means the connection above -- welcome banner included -- was actually encrypted;
        // there is no silent fallback to plain text in this code path.
        log.debug("connected to {}:{} ({})", server, port, config.tls() ? "TLS" : "plain text");
        boolean authenticated = false;
        try {
            authenticated = client.authenticate(username, password);
        } catch (IOException e) {
            throw new UsenetAuthenticationException(username, client.getReplyCode(), client.getReplyString());
        }

        if (!authenticated) {
            throw new UsenetAuthenticationException(username, client.getReplyCode(), client.getReplyString());
        }

        return client;
    }
}
