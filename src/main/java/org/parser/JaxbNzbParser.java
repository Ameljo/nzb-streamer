package org.parser;

import org.NzbUtils;
import org.example.NNTPClientFactory;
import org.model.Nzb;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import org.exceptions.NzbParseException;
import org.model.NzbFile;
import org.service.UsenetDownloadService;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;

import javax.xml.transform.sax.SAXSource;
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
            SAXSource source = new SAXSource(reader, new InputSource(input));
            Nzb nzb = (Nzb) unmarshaller.unmarshal(source);
            UsenetDownloadService downloadService = new UsenetDownloadService(NNTPClientFactory.getAuthenticatedClient(), "downloads");
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

    private XMLReader createSecureXmlReader() throws SAXException {
        XMLReader reader = XMLReaderFactory.createXMLReader();
        reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);            return reader;
    }
}
