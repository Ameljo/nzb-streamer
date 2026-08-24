package org.nzbstreamer.model;

import java.math.BigInteger;

/**
 * One article of a post. No longer its own row: {@link NzbFile} stores its segments as one JSON
 * column, so this is a plain value read from and written to that JSON, not a persisted entity.
 */
public class Segment {

    protected String value;
    protected BigInteger bytes;
    protected BigInteger number;

    private long size;

    private long startPosition; // Position of the segment in the decoded file

    public Segment() {
    }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public BigInteger getBytes() { return bytes; }
    public void setBytes(BigInteger bytes) { this.bytes = bytes; }
    public BigInteger getNumber() { return number; }
    public void setNumber(BigInteger number) { this.number = number; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public long getStartPosition() {
        return startPosition;
    }
    public void setStartPosition(long startPosition) {
        this.startPosition = startPosition;
    }
}
