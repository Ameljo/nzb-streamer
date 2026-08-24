package org.nzbstreamer.parser;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.exceptions.NzbParseException;
import org.nzbstreamer.model.Nzb;
import org.nzbstreamer.model.NzbFile;
import org.nzbstreamer.model.Segment;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads an NZB's XML by hand with SAX and {@link NzbSaxHandler}, instead of JAXB.
 *
 * <p>JAXB's runtime (accessor generation, internal reflection) is a well-known source of
 * {@code NoClassDefFoundError} on Android, and {@code javax.xml.bind}/{@code jakarta.xml.bind}
 * was never part of Android's own platform API to begin with. SAX has been part of the JDK -- and
 * of Android, since API level 1 -- so this needs nothing beyond it.</p>
 */
public class SaxNzbParser implements NzbParser {

    private static final Logger log = LogManager.getLogger(SaxNzbParser.class);

    /**
     * Reads the NZB and makes no connection to the news server.
     *
     * <p>The sizes come from the {@code bytes} attributes, which give the size of the article and
     * not always the size of the decoded segment. A caller that seeks in a post gives the result
     * to {@link org.nzbstreamer.service.NzbFileSizeResolver}, which reads the first article of
     * each post and gives the true sizes.</p>
     */
    @Override
    public Nzb parse(InputStream input) throws NzbParseException {
        try {
            long startedAt = System.nanoTime();
            XMLReader reader = createSecureXmlReader();
            NzbSaxHandler handler = new NzbSaxHandler();
            reader.setContentHandler(handler);
            reader.parse(new InputSource(input));

            Nzb nzb = handler.result();
            for (NzbFile file : nzb.getFiles()) {
                setSizesFromAttributes(file);
            }
            log.info("NZB of {} posts read in {} ms", nzb.getFiles().size(),
                    (System.nanoTime() - startedAt) / 1_000_000);
            return nzb;
        } catch (Exception e) {
            throw new NzbParseException("Failed to parse NZB file", e);
        }
    }

    private void setSizesFromAttributes(NzbFile file) {
        if (file.getSegments() == null) {
            return;
        }
        long position = 0;
        for (Segment segment : file.getSegments()) {
            long bytes = segment.getBytes() == null ? 0 : segment.getBytes().longValue();
            segment.setSize(bytes);
            segment.setStartPosition(position);
            position += bytes;
        }
        file.setSize(position);
    }

    /**
     * Reads all bytes, strips any BOM (UTF-8, UTF-16 LE/BE) and leading whitespace,
     * then returns a fresh InputStream so the XML parser always sees '<' first.
     */
//    private InputStream stripBom(InputStream in) throws IOException {
//        byte[] bytes = in.readAllBytes();
//        int start = 0;
//        // UTF-8 BOM: EF BB BF
//        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
//            start = 3;
//        // UTF-16 BE BOM: FE FF
//        } else if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
//            start = 2;
//        // UTF-16 LE BOM: FF FE
//        } else if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
//            start = 2;
//        }
//        // Skip any leading whitespace (spaces, tabs, newlines) before the XML declaration
//        while (start < bytes.length && bytes[start] <= 0x20) {
//            start++;
//        }
//        return new ByteArrayInputStream(bytes, start, bytes.length - start);
//    }

    private XMLReader createSecureXmlReader() throws SAXException, ParserConfigurationException {
        SAXParserFactory factory = SAXParserFactory.newInstance();


        XMLReader reader = factory.newSAXParser().getXMLReader();
//        reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
//        reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
//        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
//        reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, false);
//        reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, false);
        return reader;
    }
}
