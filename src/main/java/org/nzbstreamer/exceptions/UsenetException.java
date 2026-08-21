package org.nzbstreamer.exceptions;

public class UsenetException extends Exception {

    private final int replyCode;
    private final String reply;

    public UsenetException(String message, int replyCode, String reply) {
        super(message + " (Reply Code: " + replyCode + ", Reply: " + reply + ")");
        this.replyCode = replyCode;
        this.reply = reply;
    }

    public UsenetException(String message, Throwable cause) {
        super(message, cause);
        this.replyCode = -1;
        this.reply = "";
    }

    public int getReplyCode() {
        return replyCode;
    }

    public String getReply() {
        return reply;
    }
}
