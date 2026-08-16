package org.nzbstreamer.service;

import org.apache.commons.net.nntp.NNTPClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.exceptions.ArticleUnavaliableException;
import org.nzbstreamer.exceptions.NewsGroupUnavailableException;

import java.io.IOException;
import java.io.Reader;

/**
 * One connection to the news server, and the newsgroup that it selected.
 *
 * <p>The class holds the client of NNTP and gives no operation that reaches it. Thus a caller
 * cannot close the connection and cannot select another newsgroup on it. Only
 * {@link UsenetConnectionPool} and {@link PooledClientFactory} do those.</p>
 *
 * <p>A connection keeps its newsgroup. {@link #selectNewsgroup(String)} sends the command GROUP
 * only when the group changes, and it keeps the name of the group at the same moment. Thus the
 * name and the group of the server cannot say two different things.</p>
 */
public final class PooledClient {

    private static final Logger log = LogManager.getLogger(PooledClient.class);

    /** The reply code of the command DATE when the server answers it. */
    private static final int DATE_REPLY = 111;

    private final NNTPClient client;
    private String group;

    PooledClient(NNTPClient client) {
        this.client = client;
    }

    // ---- the operations of the callers ---------------------------------------------------

    /**
     * Reads one article.
     *
     * <p>The caller must close the reader, and the reader must arrive at the end of the answer
     * before the connection takes another command. A caller that stops in the middle of an
     * article gives the connection back with {@link UsenetConnectionPool#discard(PooledClient)}.
     * </p>
     *
     * @throws ArticleUnavaliableException if the server does not have the article
     */
    public Reader retrieveArticle(String messageId) throws IOException, ArticleUnavaliableException {
        Reader reader = client.retrieveArticle(messageId);
        if (reader == null) {
            throw new ArticleUnavaliableException(messageId, client.getReplyCode(),
                    client.getReplyString());
        }
        return reader;
    }

    /** The newsgroup of the connection, or null when it selected none. */
    public String group() {
        return group;
    }

    public int replyCode() {
        return client.getReplyCode();
    }

    public String replyString() {
        return client.getReplyString();
    }

    // ---- the operations of the pool ------------------------------------------------------

    /**
     * Sends the command GROUP when the connection has another group, and does nothing when it
     * has that group already.
     */
    void selectNewsgroup(String group) throws NewsGroupUnavailableException {
        if (group.equals(this.group)) {
            return;
        }
        try {
            if (!client.selectNewsgroup(group)) {
                throw new NewsGroupUnavailableException(group, client.getReplyCode(),
                        client.getReplyString());
            }
        } catch (IOException e) {
            throw new NewsGroupUnavailableException(group, client.getReplyCode(),
                    client.getReplyString());
        }
        this.group = group;
    }

    /**
     * Says that the connection answers.
     *
     * <p>The command DATE is the cheapest one that the server answers. The operation
     * {@code isConnected} of the client gives the state of the socket of this side only, thus it
     * says that a connection is good after the server closed it.</p>
     */
    boolean isAlive() {
        try {
            return client.isConnected() && client.sendCommand("DATE") == DATE_REPLY;
        } catch (IOException e) {
            log.debug("a connection of the pool does not answer: {}", e.getMessage());
            return false;
        }
    }

    /** Closes the connection. */
    void close() {
        try {
            client.disconnect();
        } catch (IOException e) {
            log.debug("cannot close a connection: {}", e.getMessage());
        }
    }
}
