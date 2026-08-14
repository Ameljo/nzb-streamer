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
import org.nzbstreamer.model.NzbFile;
import org.nzbstreamer.parser.NzbParserFactory;
import org.nzbstreamer.repository.ApplicationContextUtil;
import org.nzbstreamer.service.NNTPClientFactory;
import org.nzbstreamer.service.UsenetDownloadService;
import org.nzbstreamer.transformers.NzbFileTransformer;
import org.nzbstreamer.transformers.TikaNzbFileTransformer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.io.support.ResourcePropertySource;
import org.xml.sax.SAXException;

import java.io.*;
import java.util.List;

public class MetadataMain {
    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(MetadataMain.class);
    public static void main(String[] args) throws Exception {
        // JaxbNzbParser and the segment stream get UsenetDownloadService from the static holder in
        // ApplicationContextUtil. Thus this example needs a context with those beans.
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources()
                .addFirst(new ResourcePropertySource("classpath:application-local.properties"));
        context.register(PropertySourcesPlaceholderConfigurer.class, ApplicationContextUtil.class,
                NNTPClientFactory.class, UsenetDownloadService.class);
        context.refresh();

        Nzb nzb = NzbParserFactory.createParser().parse(new FileInputStream("downloads/test2.nzb"));
        NzbFileTransformer<VirtualFile> transformer = new TikaNzbFileTransformer();

        // A volume of an archive gives the files in it. A volume with the continuation of a file
        // gives nothing. Thus this example uses the first file of the NZB that gives a result.
        VirtualFile vf = null;
        for (NzbFile nzbFile : nzb.getFiles()) {
            List<VirtualFile> files = transformer.transform(nzbFile);
            if (files != null && !files.isEmpty()) {
                vf = files.getFirst();
                break;
            }
        }
        if (vf == null) {
            log.warn("no virtual file in the NZB");
            return;
        }
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
