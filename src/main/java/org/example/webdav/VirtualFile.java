package org.example.webdav;

import org.model.Nzb;

public class VirtualFile {
    private final Long size;
    private final String filename;
    private final Nzb.File nzbFile;

    public VirtualFile(long size, String filename, Nzb.File nzbFile) {
        this.nzbFile = nzbFile;
        this.size = size;
        this.filename = filename;
    }

    public long getSize() { return size; }
    public String filename() { return filename; }

    public Nzb.File getNzbFile() { return nzbFile; }
}
