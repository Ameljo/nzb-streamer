package org.nzbstreamer.model;

import jakarta.persistence.*;
import org.nzbstreamer.streams.VirtualFileInputStream;

import java.io.InputStream;
import java.util.UUID;

@Entity
@Table(name = "virtual_files")
public class VirtualFile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private Long size;
    private String filename;

    @ManyToOne(cascade = CascadeType.ALL)
    private NzbFile nzbFile;

    private String contentType;

    @Column(name = "\"offset\"")
    private long offset = 0;

    public VirtualFile() {
    }

    public VirtualFile(long size, String filename, NzbFile nzbFile) {
        this.nzbFile = nzbFile;
        this.size = size;
        this.filename = filename;
    }

    public long getSize() {
        return size;
    }

    public String filename() {
        return filename;
    }

    public NzbFile getNzbFile() {
        return nzbFile;
    }

    public InputStream getInputStream() throws Exception {
        return new VirtualFileInputStream(this);
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public void setNzbFile(NzbFile nzbFile) {
        this.nzbFile = nzbFile;
    }

    public long getOffset() {
        return offset;
    }

    public void setOffset(long offset) {
        this.offset = offset;
    }

    public int getSegmentAtPosition(long position) {
        return nzbFile.getSegmentAtPosition(offset + position);
    }
}
