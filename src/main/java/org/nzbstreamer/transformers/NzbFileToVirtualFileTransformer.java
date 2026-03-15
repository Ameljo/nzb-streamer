package org.nzbstreamer.transformers;

import net.sf.sevenzipjbinding.IInArchive;
import net.sf.sevenzipjbinding.PropID;
import net.sf.sevenzipjbinding.PropertyInfo;
import net.sf.sevenzipjbinding.SevenZip;
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.model.NzbFile;
import org.nzbstreamer.streams.RARIInVirtualStream;
import org.nzbstreamer.streams.VirtualFileInputStream;
import org.nzbstreamer.utils.NzbUtils;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.nzbstreamer.utils.NzbUtils.sanitizeFileName;

public class NzbFileToVirtualFileTransformer implements NzbFileTransformer<VirtualFile> {
    private static final org.apache.logging.log4j.Logger log = org.apache.logging.log4j.LogManager.getLogger(NzbFileToVirtualFileTransformer.class);

    @Override
    public List<VirtualFile> transform(NzbFile file) {
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
        if(!NzbUtils.isMediaType(virtualFile.getContentType()))
            return null;
        return List.of(virtualFile);
    }
}
