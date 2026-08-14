package org.nzbstreamer.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.exceptions.NzbParseException;
import org.nzbstreamer.model.Nzb;
import org.nzbstreamer.model.NzbFile;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.model.VirtualResource;
import org.nzbstreamer.parser.NzbParser;
import org.nzbstreamer.parser.NzbParserFactory;
import org.nzbstreamer.repository.VirtualFileRepository;
import org.nzbstreamer.repository.VirtualResourceRepository;
import org.nzbstreamer.transformers.NzbFileTransformer;
import org.nzbstreamer.transformers.NzbTransformerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Service
public class NzbProcessingService {

    private static final Logger log = LogManager.getLogger(NzbProcessingService.class);

    @Autowired
    private VirtualFileRepository virtualFileRepository;

    @Autowired
    private VirtualResourceRepository virtualResourceRepository;

    @Autowired
    private NzbTransformerFactory nzbTransformerFactory;

    /**
     * Process an uploaded NZB file
     *
     * @param inputStream The input stream of the .nzb file
     * @param filename The original filename
     * @return The parsed Nzb object
     * @throws NzbParseException if parsing fails
     */
    @Transactional
    public Nzb processNzbFile(InputStream inputStream, String filename) throws NzbParseException {
        log.info("Processing NZB file: {}", filename);

        try {
            // Parse the NZB file
            NzbParser parser = NzbParserFactory.createParser();
            Nzb nzb = parser.parse(inputStream);

            log.info("Successfully parsed NZB file: {} with {} files", filename, nzb.getFiles().size());

            // Transform NZB files to virtual files and persist them
            List<VirtualFile> virtualFiles = new ArrayList<>();

            // One transformer reads all the NZB, because a file of an archive can continue from
            // one volume to the next volume.
            List<VirtualFile> found = nzbTransformerFactory.getTransformer(nzb).transform(nzb);
            if (found != null) {
                virtualFiles.addAll(found);
            }

            // Save virtual files
            virtualFileRepository.saveAll(virtualFiles);
            log.info("Saved {} virtual files to repository", virtualFiles.size());

            // Create virtual resources for WebDAV access
            VirtualResource root = virtualResourceRepository.findByPath("/webdav");
            if (root == null) {
                root = createRootResource();
            }

            // Create a folder for this NZB file
            String folderName = filename.endsWith(".nzb") ? filename.substring(0, filename.length() - 4) : filename;
            String folderPath = "/webdav/" + folderName;

            VirtualResource nzbFolder = virtualResourceRepository.findByPath(folderPath);
            if (nzbFolder == null) {
                nzbFolder = new VirtualResource();
                nzbFolder.setName(folderName);
                nzbFolder.setFolder(true);
                nzbFolder.setPath(folderPath);
                nzbFolder.setParent(root);
                virtualResourceRepository.save(nzbFolder);
                log.info("Created folder for NZB: {}", folderName);
            }

            // Create virtual resources inside the NZB folder
            for (VirtualFile virtualFile : virtualFiles) {
                VirtualResource resource = new VirtualResource();
                resource.setName(virtualFile.filename());
                resource.setFolder(false);
                resource.setFile(virtualFile);
                resource.setPath(folderPath + "/" + virtualFile.filename());
                resource.setParent(nzbFolder);
                virtualResourceRepository.save(resource);
                log.debug("Created virtual resource for: {}", virtualFile.filename());
            }

            log.info("NZB file processing completed successfully: {}", filename);
            return nzb;

        } catch (Exception e) {
            log.error("Failed to process NZB file: {}", filename, e);
            throw new NzbParseException("Failed to process NZB file: " + filename, e);
        }
    }


    public Nzb processNzbFileWithoutSaving(InputStream inputStream, String filename) throws NzbParseException {
        log.info("Processing NZB file: {}", filename);

        try {
            // Parse the NZB file
            NzbParser parser = NzbParserFactory.createParser();
            Nzb nzb = parser.parse(inputStream);

            log.info("Successfully parsed NZB file: {} with {} files", filename, nzb.getFiles().size());

            nzbTransformerFactory.getTransformer(nzb).transform(nzb);
            return nzb;
        } catch (Exception e) {
            log.error("Failed to process NZB file: {}", filename, e);
            throw new NzbParseException("Failed to process NZB file: " + filename, e);
        }
    }

    private VirtualResource createRootResource() {
        VirtualResource root = new VirtualResource();
        root.setName("webdav");
        root.setFolder(true);
        root.setPath("/webdav");
        root.setParent(null);
        virtualResourceRepository.save(root);
        log.info("Created root WebDAV resource");
        return root;
    }
}

