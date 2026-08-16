package org.nzbstreamer.service;

import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.nzbstreamer.exceptions.UsenetException;

/**
 * Opens and closes the connections of the pool.
 *
 * <p>The pool asks this class for a connection when it has none free, and it asks
 * {@link #validateObject} while a connection waits. Thus a connection that the server closed
 * leaves the pool before a caller takes it.</p>
 */
public class PooledClientFactory extends BasePooledObjectFactory<PooledClient> {

    private final NNTPClientFactory clientFactory;

    public PooledClientFactory(NNTPClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    /**
     * The interface says {@code throws Exception}. This operation says which exceptions it
     * throws, thus the pool gives them back to the caller as they are.
     */
    @Override
    public PooledClient create() throws UsenetException {
        return new PooledClient(clientFactory.createClient());
    }

    @Override
    public PooledObject<PooledClient> wrap(PooledClient pooled) {
        return new DefaultPooledObject<>(pooled);
    }

    @Override
    public boolean validateObject(PooledObject<PooledClient> pooled) {
        return pooled.getObject().isAlive();
    }

    @Override
    public void destroyObject(PooledObject<PooledClient> pooled) {
        pooled.getObject().close();
    }
}
