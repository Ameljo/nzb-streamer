package org.nzbstreamer.exceptions;

/**
 * All the connections of the pool are in use, and none came free before the wait ended.
 */
public class PoolExhaustedException extends UsenetException {

    private final int size;

    public PoolExhaustedException(int size, Throwable cause) {
        super("All " + size + " connections of the pool are in use", cause);
        this.size = size;
    }

    public int getSize() {
        return size;
    }
}
