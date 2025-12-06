package org.transformers;

import org.apache.tika.Tika;
import org.webdav.VirtualFile;
import org.model.NzbFile;

import java.io.InputStream;

import static org.NzbUtils.sanitizeFileName;

public class NzbFileToVirtualFileTransformer implements NzbFileTransformer<VirtualFile> {
    private static final org.apache.logging.log4j.Logger log = org.apache.logging.log4j.LogManager.getLogger(NzbFileToVirtualFileTransformer.class);
    @Override
    public VirtualFile transform(NzbFile file) {
        String filename = sanitizeFileName(file.getSubject());
        VirtualFile virtualFile = new VirtualFile(file.getSize(), filename, file);
        Tika tika = new Tika();
        try(InputStream inputStream = virtualFile.getInputStream()) {
            String contentType = tika.detect(inputStream, filename);
            virtualFile.setContentType(contentType);
        } catch (Exception e) {
            virtualFile.setContentType("application/octet-stream");
            log.error("Error detecting content type for file {}: {}", filename, e.getMessage());
        }
        return virtualFile;
    }
}
