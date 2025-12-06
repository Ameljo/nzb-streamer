package org.webdav;

import org.apache.tika.metadata.Metadata;
import org.model.NzbFile;

import java.io.InputStream;

public class VirtualFile {
    private final Long size;
    private final String filename;
    private final NzbFile nzbFile;
    private Metadata metadata;
    private String contentType;

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
        return new OnDemandNzbInputStream(this);
    }

    public void setMetadata(Metadata metadata) {
        this.metadata = metadata;
    }

    public Metadata getMetadata() {
        return metadata;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }
}
