package org.example;

import org.apache.commons.net.nntp.NNTPClient;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class NNTPClientFactory {
    public static NNTPClient getAuthenticatedClient() throws IOException {
        Properties props = new Properties();
        try (InputStream in = NNTPClientFactory.class.getClassLoader().getResourceAsStream("nntp.properties")) {
            if (in == null) throw new IOException("nntp.properties not found");
            props.load(in);
        }
        String server = props.getProperty("server");
        int port = Integer.parseInt(props.getProperty("port"));
        String username = props.getProperty("username");
        String password = props.getProperty("password");

        NNTPClient client = new NNTPClient();
        client.connect(server, port);
        client.authenticate(username, password);
        return client;
    }
}

