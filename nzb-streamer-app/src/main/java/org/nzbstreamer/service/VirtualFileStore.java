package org.nzbstreamer.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.entity.VirtualFileEntity;
import org.nzbstreamer.entity.VirtualFileMapper;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.model.VirtualResource;
import org.nzbstreamer.repository.VirtualFileRepository;
import org.nzbstreamer.repository.VirtualResourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Saves the files of an NZB and puts them in the tree of WebDAV.
 *
 * <p>This class holds the only transaction of an upload, and it makes no connection to the news
 * server. {@link NzbProcessingService} reads the NZB and the articles before it calls this class,
 * thus a transaction stays open for the writes of the database and not for the minutes of a
 * conversation with the news server.</p>
 */
@Service
public class VirtualFileStore {

    private static final Logger log = LogManager.getLogger(VirtualFileStore.class);

    private static final String ROOT_PATH = "/webdav";

    private final VirtualFileRepository fileRepository;
    private final VirtualResourceRepository resourceRepository;

    public VirtualFileStore(VirtualFileRepository fileRepository,
                            VirtualResourceRepository resourceRepository) {
        this.fileRepository = fileRepository;
        this.resourceRepository = resourceRepository;
    }

    /**
     * Saves the files and gives each one a resource of WebDAV, in a folder of the name of the NZB.
     *
     * <p>If the NZB contains image files, the first image is used as the thumbnail for all
     * non-image media files. Image thumbnails are saved and accessible but are flagged via the
     * {@code thumbnailId} field on the media files that reference them.</p>
     */
    @Transactional
    public void save(List<VirtualFile> files, String folderName) {
        List<VirtualFileEntity> entities = VirtualFileMapper.toEntities(files);

        // Save all files first so that IDs are assigned by the database.
        fileRepository.saveAll(entities);

        // Associate the first image file (if any) as the thumbnail for all video/audio files.
        entities.stream()
                .filter(f -> f.getContentType() != null && f.getContentType().startsWith("image/"))
                .findFirst()
                .ifPresent(thumbnail -> {
                    entities.stream()
                            .filter(f -> f.getContentType() != null
                                    && !f.getContentType().startsWith("image/"))
                            .forEach(f -> f.setThumbnailId(thumbnail.getId()));
                    fileRepository.saveAll(entities);
                });

        log.info("Saved {} virtual files to repository", entities.size());

        VirtualResource folder = folderOf(folderName);
        int added = 0;
        int alreadyThere = 0;
        for (VirtualFileEntity entity : entities) {
            String path = folder.getPath() + "/" + entity.getFilename();
            // The tree holds one resource for a path. A caller that adds the same NZB a second
            // time, or another NZB that holds a file of the same name in the same folder, keeps
            // the resource of before: the path names the file of the tree, not the upload.
            if (resourceRepository.findByPath(path) != null) {
                log.info("{} is in the tree already, thus it keeps the resource of before", path);
                alreadyThere++;
                continue;
            }
            VirtualResource resource = new VirtualResource();
            resource.setName(entity.getFilename());
            resource.setFolder(false);
            resource.setFile(entity);
            resource.setPath(path);
            resource.setParent(folder);
            resourceRepository.save(resource);
            log.debug("Created virtual resource for: {}", entity.getFilename());
            added++;
        }
        log.info("{}: {} files added, {} in the tree already", folder.getPath(), added,
                alreadyThere);
    }

    /** The folder of one NZB. It makes the folder when the tree does not hold it yet. */
    private VirtualResource folderOf(String folderName) {
        String folderPath = ROOT_PATH + "/" + folderName;
        VirtualResource folder = resourceRepository.findByPath(folderPath);
        if (folder != null) {
            return folder;
        }
        folder = new VirtualResource();
        folder.setName(folderName);
        folder.setFolder(true);
        folder.setPath(folderPath);
        folder.setParent(root());
        resourceRepository.save(folder);
        log.info("Created folder for NZB: {}", folderName);
        return folder;
    }

    /** The root of the tree. It makes the root when the first NZB arrives. */
    private VirtualResource root() {
        VirtualResource root = resourceRepository.findByPath(ROOT_PATH);
        if (root != null) {
            return root;
        }
        root = new VirtualResource();
        root.setName("webdav");
        root.setFolder(true);
        root.setPath(ROOT_PATH);
        root.setParent(null);
        resourceRepository.save(root);
        log.info("Created root WebDAV resource");
        return root;
    }
}
