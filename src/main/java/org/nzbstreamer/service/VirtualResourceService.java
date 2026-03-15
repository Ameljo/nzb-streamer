package org.nzbstreamer.service;

import io.milton.resource.Resource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.model.VirtualResource;
import org.nzbstreamer.repository.VirtualFileRepository;
import org.nzbstreamer.repository.VirtualResourceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.nzbstreamer.streams.VirtualFileInputStream;
import org.nzbstreamer.webdav.VirtualFileResource;
import org.nzbstreamer.webdav.VirtualFolderResource;

import java.util.ArrayList;
import java.util.List;

@Service
public class VirtualResourceService {
    private static final Logger log = LogManager.getLogger(VirtualResourceService.class);
    private final VirtualResourceRepository resourceRepository;
    private final VirtualFileRepository fileRepository;

    @Autowired
    public VirtualResourceService(VirtualResourceRepository resourceRepository, VirtualFileRepository fileRepository) {
        this.resourceRepository = resourceRepository;
        this.fileRepository = fileRepository;
    }

    @Transactional
    public Resource getResource(String host, String url) {
        VirtualResource r = resourceRepository.findByPath(url);
        if (r != null) {
            if (r.isFolder()) {
                log.debug("returning folder resource for path: " + url);
                VirtualFolderResource folder = new VirtualFolderResource(null, r.getName(), r.getPath());
                List<Resource> children = new ArrayList<>();
                for (VirtualResource rChild : r.getChildren()) {
                    if (rChild.isFolder()) {
                        Resource resource = new VirtualFolderResource(folder, rChild.getName(), r.getPath());
                        children.add(resource);
                    } else {
                        VirtualFile vf = rChild.getFile();
                        children.add(new VirtualFileResource(new VirtualFileInputStream(vf), folder));
                    }
                }
                folder.setChildren(children);
                return folder;
            } else {
                log.debug("returning file resource for path: " + url);
                VirtualFile vf = r.getFile();
                return new VirtualFileResource(new VirtualFileInputStream(vf), null);
            }
        }
        log.debug("_found: " + r + " for url: " + url + " (adjusted: " + url + ") and path: " + url);
        return null;
    }
}
