package org.example;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.model.VirtualResource;
import org.nzbstreamer.repository.VirtualFileRepository;
import org.nzbstreamer.repository.VirtualResourceRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;
import org.nzbstreamer.webdav.VirtualResourceFactory;

@SpringBootApplication(scanBasePackages = "org.nzbstreamer")
@EntityScan(basePackages = {"org.nzbstreamer.model"})
@EnableConfigurationProperties
public class Main {
    private static final Logger logger = LogManager.getLogger(Main.class.getName());


    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @Bean
    @Transactional
    public CommandLineRunner loadSampleData(VirtualResourceFactory factory, VirtualFileRepository virtualFileRepository, VirtualResourceRepository virtualResourceRepository) {
        return args -> {
            VirtualResource root = virtualResourceRepository.findByPath("/webdav");
            if (root == null) {
                root = new VirtualResource();
                root.setName("webdav");
                root.setPath("/webdav");
                root.setFolder(true);
                virtualResourceRepository.save(root);
            }
            logger.info("WebDAV server is running at http://localhost:8080/webdav");
        };
    }
}
