package org.nzbstreamer.transformers;

import org.apache.tika.Tika;
import org.nzbstreamer.model.Nzb;
import org.nzbstreamer.model.NzbFile;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.utils.NzbUtils;
import org.springframework.stereotype.Component;

@Component
public class NzbTransformerFactory {
    private final NzbTransformer<String> nzbToStringTransformer = new NzbToStringTransformer();
    private final NzbFileTransformer<VirtualFile> nzbFileToVirtualFileTransformer = new NzbFileToVirtualFileTransformer();
    private final NzbFileTransformer<VirtualFile> nzbRarFileToVirtualFileTransformer = new NzbRarFileToVirtualFileTransformer();


    public NzbTransformer<String> getTransformer(Nzb nzb) {
        // For simplicity, always return NzbToStringTransformer
        return nzbToStringTransformer;
    }

    public NzbFileTransformer<VirtualFile> getTransformer(NzbFile file) {
        // For simplicity, always return NzbFileToVirtualFileTransformer
        String filename = NzbUtils.sanitizeFileName(file.getSubject());
        Tika tika = new Tika();
        String contentType = tika.detect(filename);
        if("application/x-rar-compressed".equals(contentType)) {
            return nzbRarFileToVirtualFileTransformer;
        }
        return new NzbFileToVirtualFileTransformer();
    }
}
