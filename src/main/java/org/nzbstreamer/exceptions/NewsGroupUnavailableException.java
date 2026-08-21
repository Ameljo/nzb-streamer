package org.nzbstreamer.exceptions;

import org.apache.commons.net.nntp.NNTPClient;

public class NewsGroupUnavailableException extends UsenetException{
    private final String group;

    public NewsGroupUnavailableException(String group, int replyCode, String replyString) {
        super("Cannot access newsgroup: " + group, replyCode, replyString);
        this.group = group;
    }

    public String getGroup() {
        return group;
    }
}
