package org.nzbstreamer.model;

import jakarta.persistence.*;
import jakarta.xml.bind.annotation.*;
import java.math.BigInteger;
import java.util.UUID;

@Entity
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = {"value"})
public class Segment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @XmlTransient
    private UUID id;
    @XmlValue
    protected String value;
    @XmlAttribute(name = "bytes", required = true)
    protected BigInteger bytes;
    @XmlAttribute(name = "number", required = true)
    protected BigInteger number;

    @XmlTransient
    private long size;

    @XmlTransient
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