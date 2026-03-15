package org.nzbstreamer.transformers;

import net.sf.sevenzipjbinding.IInArchive;
import net.sf.sevenzipjbinding.PropID;
import net.sf.sevenzipjbinding.PropertyInfo;
import net.sf.sevenzipjbinding.SevenZip;
import net.sf.sevenzipjbinding.simple.ISimpleInArchive;
import net.sf.sevenzipjbinding.simple.ISimpleInArchiveItem;
import org.apache.tika.Tika;
import org.nzbstreamer.model.NzbFile;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.streams.RARIInVirtualStream;
import org.nzbstreamer.streams.VirtualFileInputStream;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.nzbstreamer.utils.NzbUtils.sanitizeFileName;

public class NzbRarFileToVirtualFileTransformer  implements  NzbFileTransformer<VirtualFile>{
    private static final org.apache.logging.log4j.Logger log = org.apache.logging.log4j.LogManager.getLogger(NzbRarFileToVirtualFileTransformer.class);

    public List<VirtualFile> transform(NzbFile file) {
        long transformStart = System.nanoTime();
        String filename = sanitizeFileName(file.getSubject());
        VirtualFile virtualFile = new VirtualFile(file.getSize(), filename, file);
        Tika tika = new Tika();
        if(!filename.toLowerCase().endsWith(".rar")) {
            log.warn("File {} does not have a .rar extension. Skipping RAR processing.", filename);
            return null;
        }

        long offset = 0;
        List<VirtualFile> files = new ArrayList<>();

        long extractionStart = System.nanoTime();
        if (file.getSubject().contains(".part")) {
//            throw new UnsupportedOperationException("Multi-part RAR files are not supported.");
        }
        try (InputStream inputStream = virtualFile.getInputStream()) {
            IInArchive inArchive = SevenZip.openInArchive(null, new RARIInVirtualStream((VirtualFileInputStream) inputStream));
            for (PropID pid : PropID.values()) {
                try {
                    log.debug("RAR Archive Property - {}: {}", pid, inArchive.getProperty(0, pid));
                } catch (Exception e) {
                    log.debug("Could not read RAR archive property for PropID {}: {}", pid, e.getMessage());
                }
            }
//            ISimpleInArchive simpleInArchive = inArchive.getSimpleInterface();
//            for (ISimpleInArchiveItem item : simpleInArchive.getArchiveItems()) {
//                String partName = item.getPath();
//                Long partSize = item.getSize();
//                VirtualFile partFile = new VirtualFile(partSize, partName, file);
//                long contentTypeStart = System.nanoTime();
//                String contentType = tika.detect(partName);
//                long contentTypeEnd = System.nanoTime();
//                partFile.setContentType(contentType);
//                partFile.setOffset(offset);
//                log.info("RAR part: {} size: {} offset: {} header: {} (contentType detection: {} ms)", partName, partSize, offset, contentType, (contentTypeEnd - contentTypeStart) / 1_000_000);
//                offset += partSize;
//                files.add(partFile);
//            }
        } catch (Exception e) {
            virtualFile.setContentType("application/octet-stream");
            log.error("Error detecting content type for file {}: {}", filename, e.getMessage());
        }
        long extractionEnd = System.nanoTime();
        log.info("RAR extraction took {} ms", (extractionEnd - extractionStart) / 1_000_000);

        log.info("Total size of RAR files: {} bytes", offset);
        log.info("Original file size: {} bytes", file.getSize());
        log.info("Difference in size after RAR extraction: {} bytes", file.getSize() - (offset));

        for (VirtualFile part : files) {
            int start = 0;
            byte[] headerBytes = new byte[4096];
            long fileStartDetectStart = System.nanoTime();
            try (InputStream is = part.getInputStream()) {
//                while (start <= 0) {
                    int read  = is.read(headerBytes);
                    if (read <= 0) {
                        log.warn("Could not read header bytes for file {}", part.filename());
                        break;
                    }
                    start = FileStartFinder.findFileStart(headerBytes, part.getContentType());
//                }
                long fileStartDetectEnd = System.nanoTime();
                log.info("File start for part {} found at offset: {} (detection took {} ms)", part.filename(), start, (fileStartDetectEnd - fileStartDetectStart) / 1_000_000);
            } catch (Exception e) {
                log.error("Error reading header bytes for file {}: {}", part.filename(), e.getMessage());
            }
            if (start > 0) {
                part.setOffset(part.getOffset() + start);
            }
        }
        long transformEnd = System.nanoTime();
        log.info("Total transform() time: {} ms", (transformEnd - transformStart) / 1_000_000);
        return files;
    }

    public static class FileStartFinder {
        /**
         * Finds the offset in the byte array where the given content type is detected by Tika.
         * @param data The byte array containing the file data (possibly with extra bytes at the start).
         * @param expectedContentType The MIME type to detect (e.g., "audio/mpeg").
         * @return The offset where the content type is detected, or -1 if not found.
         */
        public static int findFileStart(byte[] data, String expectedContentType) {
            Tika tika = new Tika();
            for (int offset = 0; offset <= data.length; offset++) {
                byte[] subArray = new byte[data.length - offset];
                System.arraycopy(data, offset, subArray, 0, data.length - offset);
                String detected = tika.detect(subArray);
                if (expectedContentType.equals(detected)) {
                    return offset;
                }
            }
            return -1;
        }
    }
}
