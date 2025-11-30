package org.example.webdav;

import io.milton.http.Range;
import io.milton.http.Auth;
import io.milton.http.exceptions.*;
import io.milton.resource.GetableResource;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

public class VirtualWebDavFile extends TResource implements GetableResource {

    private static final org.apache.log4j.Logger log = org.apache.log4j.Logger.getLogger(VirtualWebDavFile.class);

    private final VirtualFile vf;

    public VirtualWebDavFile(OnDemandNzbInputStream inputStream, TFolderResource parent) {
        super(parent, inputStream.getFile().filename());
        this.vf = inputStream.getFile();
    }

    @Override
    protected Object clone(TFolderResource newParent, String newName) {
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
    public void sendContent(OutputStream out, Range range, Map<String, String> params, String contentType)
            throws IOException {
        log.info("sendContent called");

        long start = 0;
        long end = vf.getSize() - 1;

        if (range != null) {
            start = range.getStart();
            end = range.getFinish();
        }

        long bytesToWrite = end - start + 1;
        OnDemandNzbInputStream nzbStream = new OnDemandNzbInputStream(vf);

        // Copy bytes to output
        byte[] buffer = new byte[739502];
        int read;
        while ((read = nzbStream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    @Override
    public Long getMaxAgeSeconds(Auth auth) {
        log.info("getMaxAgeSeconds called, returning: 3600");
        return 3600L; // optional caching
    }
}
