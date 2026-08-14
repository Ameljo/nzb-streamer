package org.nzbstreamer.transformers;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.nzbstreamer.model.NzbFile;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.rar.RarArchive;
import org.nzbstreamer.rar.RarFileEntry;
import org.nzbstreamer.rar.tika.RarArchiveCollector;
import org.nzbstreamer.rar.tika.RarHeaderTikaParser;
import org.nzbstreamer.utils.NzbUtils;
import org.xml.sax.helpers.DefaultHandler;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.nzbstreamer.utils.NzbUtils.sanitizeFileName;

/**
 * Makes the virtual files for one file of an NZB. Tika decides what the file is.
 *
 * <p>The transformer gives the stream to an {@link AutoDetectParser}. Tika reads the first bytes,
 * finds the type of the data, then calls the parser for that type:</p>
 *
 * <ul>
 *   <li>a RAR archive goes to {@link RarHeaderTikaParser}. That parser gives the entries of the
 *       archive. The transformer makes one virtual file for each entry that it can stream;</li>
 *   <li>a different type has no parser. But Tika writes the type in the metadata. The transformer
 *       makes one virtual file with that type.</li>
 * </ul>
 *
 * <p>Tika finds the type from the content and not from the name. Thus this transformer is also
 * correct for a post with an obfuscated subject, where the name gives no information.</p>
 */
public class TikaNzbFileTransformer implements NzbFileTransformer<VirtualFile> {

    private static final org.apache.logging.log4j.Logger log =
            org.apache.logging.log4j.LogManager.getLogger(TikaNzbFileTransformer.class);

    private static final String UNKNOWN_TYPE = "application/octet-stream";

    private final Parser parser;

    public TikaNzbFileTransformer() {
        // The default configuration includes the parsers of META-INF/services, thus also the
        // parser for RAR archives.
        this(new AutoDetectParser());
    }

    public TikaNzbFileTransformer(Parser parser) {
        this.parser = parser;
    }

    @Override
    public List<VirtualFile> transform(NzbFile nzbFile) {
        String filename = sanitizeFileName(nzbFile.getSubject());
        VirtualFile postedFile = new VirtualFile(nzbFile.getSize(), filename, nzbFile);

        RarArchiveCollector collector = new RarArchiveCollector();
        ParseContext context = new ParseContext();
        context.set(RarArchiveCollector.class, collector);

        Metadata metadata = new Metadata();
        // A hint for Tika. The content has more authority than this name.
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);

        try (InputStream stream = postedFile.getInputStream()) {
            parser.parse(stream, new DefaultHandler(), metadata, context);
        } catch (Exception e) {
            log.error("Cannot read {}: {}", filename, e.getMessage());
            return List.of();
        }

        String contentType = metadata.get(Metadata.CONTENT_TYPE);
        RarArchive archive = collector.archive();
        if (archive != null) {
            return archiveEntries(archive, nzbFile, filename);
        }
        return singleFile(postedFile, filename, contentType);
    }

    /** Makes one virtual file for each entry of the archive that a caller can read directly. */
    private List<VirtualFile> archiveEntries(RarArchive archive, NzbFile nzbFile, String filename) {
        log.info("{}: {} archive, {} entries, headers found with {} bytes", filename,
                archive.format(), archive.entries().size(), archive.bytesRead());

        List<VirtualFile> files = new ArrayList<>();
        for (RarFileEntry entry : archive.entries()) {
            if (!isPublishable(entry, filename)) {
                continue;
            }
            VirtualFile part = new VirtualFile(entry.packedSize(), entry.name(), nzbFile);
            part.setOffset(entry.dataOffset());
            part.setContentType(contentTypeOf(entry.name()));
            files.add(part);

            log.info("{}: entry {} at offset {}, {} bytes, {}", filename, entry.name(),
                    entry.dataOffset(), entry.packedSize(), part.getContentType());
        }
        return files;
    }

    /** Makes one virtual file for a post that is not an archive. */
    private List<VirtualFile> singleFile(VirtualFile postedFile, String filename, String contentType) {
        String type = contentType == null ? UNKNOWN_TYPE : contentType;
        postedFile.setContentType(type);
        if (!NzbUtils.isMediaType(type)) {
            log.info("{}: type {} is not a media type, thus the file stays out of the tree",
                    filename, type);
            return List.of();
        }
        log.info("{}: {}", filename, type);
        return List.of(postedFile);
    }

    /**
     * The type of a file in the archive comes from its name. The bytes of that file are at a
     * different position of the volume, thus a check of the content needs one more download.
     */
    private String contentTypeOf(String entryName) {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, entryName);
        return new org.apache.tika.Tika().detect(entryName);
    }

    /**
     * Entries that a caller cannot read directly stay out of the tree. A player cannot use them
     * without a decompression step.
     */
    private boolean isPublishable(RarFileEntry entry, String filename) {
        if (entry.directory()) {
            return false;
        }
        if (entry.encrypted()) {
            log.warn("{}: entry {} is encrypted, thus it stays out of the tree", filename,
                    entry.name());
            return false;
        }
        if (!entry.stored()) {
            log.warn("{}: entry {} uses compression method m{}. A caller cannot read it without a"
                    + " decompression step, thus it stays out of the tree", filename, entry.name(),
                    entry.method());
            return false;
        }
        if (entry.splitBefore()) {
            log.info("{}: entry {} continues from the previous volume. The volume with the start of"
                    + " the file gives this entry", filename, entry.name());
            return false;
        }
        if (entry.splitAfter()) {
            log.warn("{}: entry {} continues in the next volume. This entry gives only the {} bytes"
                    + " in this volume", filename, entry.name(), entry.packedSize());
        }
        return true;
    }
}
