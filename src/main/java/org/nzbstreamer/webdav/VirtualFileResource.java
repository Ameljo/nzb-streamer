package org.nzbstreamer.webdav;

import io.milton.annotations.BeanProperty;
import io.milton.annotations.BeanPropertyResource;
import io.milton.http.*;
import io.milton.resource.GetableResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.streams.VirtualFileInputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Date;
import java.util.Map;

@BeanPropertyResource(value = "DAV:")
public class VirtualFileResource extends AbstractResource implements GetableResource {

    private static final Logger log = LogManager.getLogger(VirtualFileResource.class);

    private final VirtualFile vf;

    private String resourcetype = null;
    private String displayname;
    private String getetag;
    private Long getcontentlength;
    private String getcontenttype;
    private Date getlastmodified;
    private Date creationdate;

    public VirtualFileResource(VirtualFileInputStream inputStream, VirtualFolderResource parent) {
        super(parent, inputStream.getFile().filename());
        this.vf = inputStream.getFile();
        this.displayname = inputStream.getFile().filename();
        this.getetag = this.id.toString();
        this.getcontentlength = inputStream.getFile().getSize();
        this.getcontenttype = vf.getContentType();
        this.getlastmodified = new Date();
        this.creationdate = new Date();
    }

    @BeanProperty
    public String getResourcetype() {
        return resourcetype;
    }

    @BeanProperty
    public String getDisplayname() {
        return displayname;
    }

    public void setDisplayname(String displayname) {
        this.displayname = displayname;
    }

    @BeanProperty
    public String getGetetag() {
        return getetag;
    }

    public void setGetetag(String getetag) {
        this.getetag = getetag;
    }

    @BeanProperty
    public Long getGetcontentlength() {
        return getcontentlength;
    }

    public void setGetcontentlength(Long getcontentlength) {
        this.getcontentlength = getcontentlength;
    }

    @BeanProperty
    public String getGetcontenttype() {
        return getcontenttype;
    }

    public void setGetcontenttype(String getcontenttype) {
        this.getcontenttype = getcontenttype;
    }

    @BeanProperty
    public Date getGetlastmodified() {
        return getlastmodified;
    }

    public void setGetlastmodified(Date getlastmodified) {
        this.getlastmodified = getlastmodified;
    }

    @BeanProperty
    public Date getCreationdate() {
        return creationdate;
    }

    public void setCreationdate(Date creationdate) {
        this.creationdate = creationdate;
    }

    @Override
    protected Object clone(VirtualFolderResource newParent, String newName) {
        log.info("clone called, returning: null");
        return null;
    }

    @Override
    public Long getContentLength() {
        Long size = vf.getSize();
        log.info("getContentLength called, returning: " + size);
        return size;
    }

    @Override
    public String getContentType(String accept) {
        String contentType;
        if (name.endsWith(".mp4")) contentType = "video/mp4";
        else if (name.endsWith(".mkv")) contentType = "video/x-matroska";
        else contentType = "application/octet-stream";
        log.info("getContentType called, returning: " + contentType);
        return contentType;
    }

    @Override
    public void sendContent(OutputStream out, Range range, Map<String, String> params, String contentType) throws IOException {
        log.info("sendContent called");

        Request request = HttpManager.request();
        Response responseHandler = HttpManager.response();
        responseHandler.setAcceptRanges("bytes");
        log.debug("Request headers: " + request.getHeaders());


        long start = 0;
        long end = vf.getSize() - 1;

        if (range != null) {
            start = range.getStart();
            // Handle open-ended ranges (e.g., "bytes=0-")
            if (range.getFinish() != null) {
                end = range.getFinish();
            }
            // Ensure end doesn't exceed file size
            end = Math.min(end, vf.getSize() - 1);
        }

        log.debug("Stream requested for range {} to {}", start, end);

        long bytesToWrite = end - start + 1;
        try (VirtualFileInputStream nzbStream = new VirtualFileInputStream(vf)) {
            if (start > 0) nzbStream.skip(start);
            int bufferLength = 65536;
            int read;
            int lengthToRead = Math.toIntExact(Math.min(bufferLength, bytesToWrite));
            byte[] buffer = new byte[lengthToRead];
            while ((read = nzbStream.read(buffer, 0, lengthToRead)) != -1 && bytesToWrite > 0) {
                log.debug("sendContent: read " + read + " bytes");
                out.write(buffer, 0, read);
                bytesToWrite -= read;
            }
            log.info("Finished sending content. Bytes left to write: {}", bytesToWrite );
        } catch (Exception e) {
            log.error("Error sending content: " + e.getMessage(), e);
            throw new IOException("Error sending content", e);
        }
    }
}
