package org.nzbstreamer.model;

import jakarta.xml.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = {
    "head",
    "files"
})
@XmlRootElement(name = "nzb")
public class Nzb {

    protected Head head;

    @XmlElement(name = "file")
    protected List<NzbFile> files;

    public Nzb() {
    }

    public Head getHead() {
        return head;
    }

    public void setHead(Head head) {
        this.head = head;
    }

    public List<NzbFile> getFiles() {
        if (files == null) {
            files = new ArrayList<>();
        }
        return this.files;
    }

    public void setFiles(List<NzbFile> nzbFiles) {
        this.files = nzbFiles;
    }

    public NzbFile getFile(int index) {
        if (files == null || index < 0 || index >= files.size()) {
            return null;
        }
        return files.get(index);
    }

    public void addFile(NzbFile nzbFile) {
        getFiles().add(nzbFile);
    }
}
