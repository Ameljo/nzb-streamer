package org.nzbstreamer.exceptions;

import org.apache.commons.net.nntp.NNTPClient;

public class ArticleUnavaliableException extends UsenetException{
    private final String message;

    public ArticleUnavaliableException(String message, int replyCode, String replyString) {
        super("Article Unavailable: " + message, replyCode, replyString);
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
