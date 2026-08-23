package org.nzbstreamer.exceptions;

import java.io.IOException;

public class UsenetAuthenticationException extends UsenetException{
    private final String username;

    public UsenetAuthenticationException(String username, int replyCode, String replyString) {
        super("Failed to authenticate with Usenet server " + username,replyCode, replyString);
        this.username = username;
    }

    public String getUsername() {
        return username;
    }
}
