package org.nzbstreamer.service;

import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nzbstreamer.exceptions.UsenetException;
import org.nzbstreamer.model.NzbFile;
import org.nzbstreamer.model.VirtualFileTestData;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that a post whose article cannot be read does not fail the whole batch, and is left
 * out of the result instead of being given a guessed size. The pool's factory fails every
 * {@code create()}, so every {@code borrow()} fails without any network call.
 */
class NzbFileSizeResolverTest {

    private static UsenetConnectionPool failingPool() {
        GenericObjectPool<PooledClient> pool = new GenericObjectPool<>(
                new BasePooledObjectFactory<>() {
                    @Override
                    public PooledClient create() throws UsenetException {
                        throw new UsenetException("simulated: no server", new IOException("no server"));
                    }

                    @Override
                    public PooledObject<PooledClient> wrap(PooledClient client) {
                        return new DefaultPooledObject<>(client);
                    }
                });
        return new UsenetConnectionPool(pool);
    }

    @Test
    @DisplayName("posts that cannot be read are left out, and the batch does not throw")
    void dropsPostsThatCannotBeRead() {
        NzbFile file1 = VirtualFileTestData.volume(1);
        NzbFile file2 = VirtualFileTestData.volume(2);

        NzbFileSizeResolver resolver = new NzbFileSizeResolver(failingPool());

        List<NzbFile> resolved = assertDoesNotThrow(
                () -> resolver.resolve(List.of(file1, file2)),
                "one bad post must not fail the whole batch");

        assertTrue(resolved.isEmpty(), "no post could be read, thus none is given back");
    }
}
