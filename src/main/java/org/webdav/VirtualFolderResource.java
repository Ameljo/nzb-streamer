package org.webdav;

import io.milton.common.StreamUtils;
import io.milton.http.Range;
import io.milton.http.Request;
import io.milton.resource.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.model.VirtualFile;
import org.repository.VirtualFileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.streams.VirtualFileInputStream;

public class VirtualFolderResource extends AbstractResource implements FolderResource {

    private static Logger log = LogManager.getLogger(AbstractResource.class);
    List<Resource> children = new ArrayList<Resource>();

    public VirtualFolderResource(VirtualFolderResource parent, String name) {
        super(parent, name);
        log.debug("created new folder: " + name);
    }

    @Override
    protected Object clone(VirtualFolderResource newParent, String newName) {
        VirtualFolderResource newFolder = new VirtualFolderResource(newParent, newName);
        for (Resource child : parent.getChildren()) {
            AbstractResource res = (AbstractResource) child;
            res.clone(newFolder, child.getName()); // will auto-add to folder
        }
        return newFolder;
    }

    @Override
    public Long getContentLength() {
        long size = 0L;
        for (Resource r : children) {
            if (r instanceof AbstractResource) {
                Long l = ((AbstractResource) r).getContentLength();
                if (l != null) {
                    size += l;
                }
            }
        }
        return size;
    }

    public String getContentType() {
        return null;
    }

    @Override
    public String checkRedirect(Request request) {
        return null;
    }

    @Override
    public List<? extends Resource> getChildren() {
        return children;
    }

    public void setChildren(List<Resource> children) {
        this.children = children;
    }

    static ByteArrayOutputStream readStream(final InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        StreamUtils.readTo(in, bos);
        return bos;
    }

    @Override
    public CollectionResource createCollection(String newName) {
        log.debug("createCollection: " + newName);
        return new VirtualFolderResource(this, newName);
    }

    @Override
    public Resource createNew(String newName, InputStream inputStream, Long length, String contentType) throws IOException {
        log.debug("createNew: " + " name: " + newName + " current child count: " + this.children.size());
        VirtualFileResource r = new VirtualFileResource((VirtualFileInputStream) inputStream, this);
        r.createdDate = new java.util.Date();
        log.debug("new child count: " + this.children.size());
        return r;
    }

    @Override
    public Resource child(String childName) {
        for (Resource r : getChildren()) {
            if (r.getName().equals(childName)) {
                return r;
            }
        }
        return null;
    }

    @Override
    public void sendContent(OutputStream out, Range range, Map<String, String> params, String contentType) {
        PrintWriter pw = new PrintWriter(out);
        pw.print("<html><body>");
        pw.print("<h1>" + this.getName() + "</h1>");
        pw.print("<p>" + this.getClass().getCanonicalName() + "</p>");
        doBody(pw);
        pw.print("</body>");
        pw.print("</html>");
        pw.flush();
    }

    protected void doBody(PrintWriter pw) {
        System.out.println("dobody - " + children.size());
        pw.print("<ul>");
        for (Resource r : this.children) {
            String href = r.getName();
            if (r instanceof CollectionResource) {
                href = href + "/";
            }
            pw.print("<li><a href='" + href + "'>" + r.getName() + "(" + r.getClass().getCanonicalName() + ")" + "</a></li>");
        }
        pw.print("</ul>");
    }

    @Override
    public String getContentType(String accepts) {
        return "text/html";
    }

}
