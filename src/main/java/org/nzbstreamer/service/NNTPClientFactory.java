package org.nzbstreamer.service;

import org.apache.commons.net.nntp.NNTPClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

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

    public NNTPClient createClient() throws IOException {
        NNTPClient client = new NNTPClient();
        client.connect(server, port);
        client.authenticate(username, password);
        return client;
    }
}
