package org.nzbstreamer.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * The persisted form of a {@link org.nzbstreamer.model.VirtualFileChunk}. Several chunks --
 * belonging to different files -- can point at the same {@link NzbFileEntity}: two files of one
 * archive use the same post.
 */
@Entity
@Table(name = "virtual_file_chunks")
public class VirtualFileChunkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // No REMOVE: two files of one archive use the same post.
    @ManyToOne(cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    private NzbFileEntity nzbFile;

    /** The position of the first byte of this chunk in the file. */
    private long fileStart;

    /** The position of the first byte of the file in the first segment of this chunk. */
    // offset is a reserved word of PostgreSQL.
    @Column(name = "\"offset\"")
    private long offset;

    /** The number of bytes of the file in this chunk. */
    private long length;

    /** The first segment of the post that holds bytes of this chunk. */
    private int firstSegment;

    /** The last segment of the post that holds bytes of this chunk. */
    private int lastSegment;

    public UUID getId() {
        return id;
    }

    public NzbFileEntity getNzbFile() {
        return nzbFile;
    }

    public void setNzbFile(NzbFileEntity nzbFile) {
        this.nzbFile = nzbFile;
    }

    public long getFileStart() {
        return fileStart;
    }

    public void setFileStart(long fileStart) {
        this.fileStart = fileStart;
    }

    public long getOffset() {
        return offset;
    }

    public void setOffset(long offset) {
        this.offset = offset;
    }

    public long getLength() {
        return length;
    }

    public void setLength(long length) {
        this.length = length;
    }

    public int getFirstSegment() {
        return firstSegment;
    }

    public void setFirstSegment(int firstSegment) {
        this.firstSegment = firstSegment;
    }

    public int getLastSegment() {
        return lastSegment;
    }

    public void setLastSegment(int lastSegment) {
        this.lastSegment = lastSegment;
    }
}
