package org.example;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.model.Nzb;
import org.parser.NzbParserFactory;
import org.repository.VirtualFileRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.transformers.NzbFileToVirtualFileTransformer;
import org.transformers.NzbFileTransformer;
import org.model.VirtualFile;
import org.webdav.VirtualResourceFactory;

import java.util.UUID;

@SpringBootApplication(scanBasePackages = {"org.example", "org.webdav", "org.config"})
@EntityScan(basePackages = {"org.model"})
public class Main {
    private static final Logger logger = LogManager.getLogger(Main.class.getName());


    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @Bean
    public CommandLineRunner loadSampleData(VirtualResourceFactory factory, VirtualFileRepository virtualFileRepository) {
        return args -> {
//            logger.info("Loading sample NZB file...");
//            VirtualFile vf = virtualFileRepository.findById(UUID.fromString("ed83c87e-88e1-4f87-b153-09a03362b994")).get();
//            factory.addFile(vf.filename(), vf);
            logger.info("WebDAV server is running at http://localhost:8080/webdav");
        };
    }
}
