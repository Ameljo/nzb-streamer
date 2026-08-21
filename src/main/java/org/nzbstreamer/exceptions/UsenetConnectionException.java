package org.nzbstreamer.exceptions;

import java.io.IOException;

public class UsenetConnectionException extends UsenetException{
    public UsenetConnectionException(String server, int port, IOException cause) {
        super("Cannot connect to " + server + ":" + port, cause);
    }
}
