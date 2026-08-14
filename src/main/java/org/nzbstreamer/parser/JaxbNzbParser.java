package org.nzbstreamer.parser;

import org.nzbstreamer.utils.NzbUtils;
import org.nzbstreamer.repository.ApplicationContextUtil;
import org.nzbstreamer.model.Nzb;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import org.nzbstreamer.exceptions.NzbParseException;
import org.nzbstreamer.model.NzbFile;
import org.nzbstreamer.service.UsenetDownloadService;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;

import javax.xml.transform.sax.SAXSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public class JaxbNzbParser implements NzbParser{
    private final JAXBContext jaxbContext;

    public JaxbNzbParser() {
        try {
            this.jaxbContext = JAXBContext.newInstance(Nzb.class);
        } catch (JAXBException e) {
            throw new IllegalStateException("Failed to initialize JAXBContext for Nzb class", e);
        }
    }

    @Override
    public Nzb parse(InputStream input) throws NzbParseException {
        try {
            Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
            XMLReader reader = createSecureXmlReader();
            SAXSource source = new SAXSource(reader, new InputSource(stripBom(input)));
            Nzb nzb = (Nzb) unmarshaller.unmarshal(source);
            UsenetDownloadService downloadService = ApplicationContextUtil.getBean(UsenetDownloadService.class);
            for (NzbFile file: nzb.getFiles()) {
                if (NzbUtils.sanitizeFileName(file.getSubject()).contains(".nfo")) {
                    continue;
                }
                downloadService.populateNzbFileSizes(file);
            }

            return nzb;
        } catch (Exception e) {
            throw new NzbParseException("Failed to parse NZB file", e);
        }
    }

    /**
     * Reads all bytes, strips any BOM (UTF-8, UTF-16 LE/BE) and leading whitespace,
     * then returns a fresh InputStream so the XML parser always sees '<' first.
     */
    private InputStream stripBom(InputStream in) throws IOException {
        byte[] bytes = in.readAllBytes();
        int start = 0;
        // UTF-8 BOM: EF BB BF
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            start = 3;
        // UTF-16 BE BOM: FE FF
        } else if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            start = 2;
        // UTF-16 LE BOM: FF FE
        } else if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            start = 2;
        }
        // Skip any leading whitespace (spaces, tabs, newlines) before the XML declaration
        while (start < bytes.length && bytes[start] <= 0x20) {
            start++;
        }
        return new ByteArrayInputStream(bytes, start, bytes.length - start);
    }

    private XMLReader createSecureXmlReader() throws SAXException {
        XMLReader reader = XMLReaderFactory.createXMLReader();
        reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);            return reader;
    }
}
