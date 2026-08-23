package org.nzbstreamer.service;

import org.apache.commons.net.nntp.NNTPClient;
import org.nzbstreamer.client.UsenetServerConfig;
import org.nzbstreamer.exceptions.UsenetAuthenticationException;
import org.nzbstreamer.exceptions.UsenetConnectionException;
import org.nzbstreamer.exceptions.UsenetException;

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class NNTPClientFactory {

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

        try {
            client.connect(server, port);
        } catch (IOException e) {
            throw new UsenetConnectionException(server, port, e);
        }
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
