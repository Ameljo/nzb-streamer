package org.example;

import org.apache.logging.log4j.Logger;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.model.Nzb;
import org.nzbstreamer.parser.NzbParserFactory;
import org.nzbstreamer.transformers.NzbFileToVirtualFileTransformer;
import org.nzbstreamer.transformers.NzbFileTransformer;
import org.xml.sax.SAXException;

import java.io.*;

public class MetadataMain {
    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(MetadataMain.class);
    public static void main(String[] args) throws Exception {
        Nzb nzb = NzbParserFactory.createParser().parse(Main.class.getResourceAsStream("/sample6.nzb"));
        NzbFileTransformer<VirtualFile> transformer = new NzbFileToVirtualFileTransformer();
        VirtualFile vf = transformer.transform(nzb.getFile(1)).getFirst();
        log.debug("test");
        Tika tika = new Tika();
        try(InputStream is = vf.getInputStream()) {
            // Extract and print metadata using Tika
            is.available();
            Metadata metadata = extractMetadata(is);
            System.out.println("Tika metadata for file: "  + vf.filename());
            for (String name : metadata.names()) {
                System.out.println(name + ": " + metadata.get(name));
            }

        }
    }

    public static Metadata extractMetadata(InputStream stream) throws IOException, TikaException, SAXException {
        AutoDetectParser parser = new AutoDetectParser();
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();
        // Use BodyContentHandler(-1) to avoid truncation for large content
        BodyContentHandler handler = new BodyContentHandler(-1);
        parser.parse(stream, handler, metadata, context);
        return metadata;
    }
}
