package org.nzbstreamer.rar.tika;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.XHTMLContentHandler;
import org.nzbstreamer.rar.RarArchive;
import org.nzbstreamer.rar.RarFileEntry;
import org.nzbstreamer.rar.RarHeaderParser;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.util.Set;

/**
 * A Tika parser that gives all the headers of a RAR archive. It does not read the files in the
 * archive.
 *
 * <p>The file {@code META-INF/services/org.apache.tika.parser.Parser} registers this parser. Thus
 * an {@code AutoDetectParser} sends a RAR stream to this parser. This parser uses
 * {@link RarHeaderParser}. That class reads the bytes of the headers and skips the data of the
 * files. This is important when the stream gets its bytes at a high cost.</p>
 *
 * <p>This parser gives the results in three forms:</p>
 *
 * <ul>
 *   <li>as {@link Metadata} entries with an index (refer to {@link RarMetadata});</li>
 *   <li>as XHTML elements {@code <div class="rar-entry">};</li>
 *   <li>as a {@link RarArchive} object. The caller puts a {@link RarArchiveCollector} in the
 *       {@link ParseContext} to get this object. The object gives the offsets as numbers.</li>
 * </ul>
 */
public class RarHeaderTikaParser implements Parser {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final Set<MediaType> SUPPORTED_TYPES = Set.of(
            MediaType.parse("application/x-rar-compressed"),
            MediaType.parse("application/x-rar-compressed;version=4"),
            MediaType.parse("application/x-rar-compressed;version=5"),
            MediaType.parse("application/x-rar"));

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED_TYPES;
    }

    @Override
    public void parse(InputStream stream, ContentHandler handler, Metadata metadata,
                      ParseContext context) throws IOException, SAXException, TikaException {

        // This method reads the stream. It does not close the stream. The caller keeps the control
        // of the stream. The Tika parser interface makes this necessary.
        RarArchive archive = new RarHeaderParser().parse(stream);

        RarArchiveCollector collector = context.get(RarArchiveCollector.class);
        if (collector != null) {
            collector.set(archive);
        }

        addArchiveMetadata(metadata, archive);

        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata);
        xhtml.startDocument();
        int index = 0;
        for (RarFileEntry entry : archive.entries()) {
            addEntryMetadata(metadata, index++, entry);
            writeEntry(xhtml, entry);
        }
        xhtml.endDocument();
    }

    private void addArchiveMetadata(Metadata metadata, RarArchive archive) {
        metadata.set(RarMetadata.FORMAT, archive.format().name());
        metadata.set(RarMetadata.VOLUME, Boolean.toString(archive.volume()));
        metadata.set(RarMetadata.VOLUME_NUMBER, Integer.toString(archive.volumeNumber()));
        metadata.set(RarMetadata.FIRST_VOLUME, Boolean.toString(archive.firstVolume()));
        metadata.set(RarMetadata.SOLID, Boolean.toString(archive.solid()));
        metadata.set(RarMetadata.END_OF_ARCHIVE, Boolean.toString(archive.endOfArchive()));
        metadata.set(RarMetadata.TRUNCATED, Boolean.toString(archive.truncated()));
        metadata.set(RarMetadata.ENTRY_COUNT, Integer.toString(archive.entries().size()));
        metadata.set(RarMetadata.BYTES_READ, Long.toString(archive.bytesRead()));
        metadata.set(RarMetadata.BYTES_SKIPPED, Long.toString(archive.bytesSkipped()));
    }

    private void addEntryMetadata(Metadata metadata, int index, RarFileEntry entry) {
        metadata.set(RarMetadata.entryKey(index, RarMetadata.ENTRY_NAME), entry.name());
        metadata.set(RarMetadata.entryKey(index, RarMetadata.ENTRY_DATA_OFFSET),
                Long.toString(entry.dataOffset()));
        metadata.set(RarMetadata.entryKey(index, RarMetadata.ENTRY_PACKED_SIZE),
                Long.toString(entry.packedSize()));
        metadata.set(RarMetadata.entryKey(index, RarMetadata.ENTRY_UNPACKED_SIZE),
                Long.toString(entry.unpackedSize()));
        metadata.set(RarMetadata.entryKey(index, RarMetadata.ENTRY_METHOD),
                Integer.toString(entry.method()));
        metadata.set(RarMetadata.entryKey(index, RarMetadata.ENTRY_STORED),
                Boolean.toString(entry.stored()));
        metadata.set(RarMetadata.entryKey(index, RarMetadata.ENTRY_DIRECTORY),
                Boolean.toString(entry.directory()));
        metadata.set(RarMetadata.entryKey(index, RarMetadata.ENTRY_ENCRYPTED),
                Boolean.toString(entry.encrypted()));
        metadata.set(RarMetadata.entryKey(index, RarMetadata.ENTRY_SPLIT_BEFORE),
                Boolean.toString(entry.splitBefore()));
        metadata.set(RarMetadata.entryKey(index, RarMetadata.ENTRY_SPLIT_AFTER),
                Boolean.toString(entry.splitAfter()));
        if (entry.crc32() != null) {
            metadata.set(RarMetadata.entryKey(index, RarMetadata.ENTRY_CRC32),
                    String.format("%08X", entry.crc32()));
        }
    }

    private void writeEntry(XHTMLContentHandler xhtml, RarFileEntry entry) throws SAXException {
        xhtml.startElement("div", "class", "rar-entry");
        xhtml.element("h3", entry.name());
        xhtml.element("p", "dataOffset=" + entry.dataOffset()
                + " packedSize=" + entry.packedSize()
                + " unpackedSize=" + entry.unpackedSize()
                + " method=m" + entry.method()
                + (entry.directory() ? " directory" : "")
                + (entry.encrypted() ? " encrypted" : "")
                + (entry.splitBefore() ? " splitBefore" : "")
                + (entry.splitAfter() ? " splitAfter" : ""));
        xhtml.endElement("div");
    }
}
