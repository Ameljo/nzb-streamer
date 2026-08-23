package org.nzbstreamer.service;

import org.apache.commons.net.nntp.NNTPClient;
import org.nzbstreamer.exceptions.UsenetAuthenticationException;
import org.nzbstreamer.exceptions.UsenetConnectionException;
import org.nzbstreamer.exceptions.UsenetException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class NNTPClientFactory {

    @Value("${usenet.server}")
    private String server;

    @Value("${usenet.port}")
    private int port;

    @Value("${usenet.username}")
    private String username;

    @Value("${usenet.password}")
    private String password;

    @Value("${nntp.connection-timeout:10000}")
    private int connectionTimeout;

    @Value("${nntp.read-timeout:10000}")
    private int readTimeout;

    public NNTPClient createClient() throws UsenetException {
        NNTPClient client = new NNTPClient();
        client.setCharset(StandardCharsets.ISO_8859_1);
        client.setConnectTimeout(connectionTimeout);
        client.setDefaultTimeout(readTimeout);

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
