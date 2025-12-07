package org.webdav;


import io.milton.common.Path;
import io.milton.http.ResourceFactory;
import io.milton.resource.Resource;
import jakarta.annotation.PostConstruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.model.VirtualFile;
import org.repository.VirtualFileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.streams.VirtualFileInputStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class VirtualResourceFactory implements ResourceFactory {

    private static final Logger log = LogManager.getLogger(VirtualResourceFactory.class);
    private static VirtualResourceFactory instance;


    @Autowired
    private VirtualFileRepository fileRepository;

    public final VirtualFolderResource ROOT;
    final Map<String, VirtualFile> filesMap = new HashMap<>();
    final Map<String,String> credentialsMap = new HashMap<>();

    public VirtualResourceFactory() {
        // Implement singleton pattern - if instance already exists, reuse its data
        if (instance == null) {
            instance = this;
            ROOT = new VirtualFolderResource(null, "http://localhost:8080/webdav");
            addUser("usera", "password");
            addUser("userb", "password");
            addUser("userv", "password");
            log.info("VirtualWebDavFactory: Created new instance");
        } else {
            // Milton is creating a second instance, redirect to the singleton
            log.info("VirtualWebDavFactory: Reusing existing singleton instance");
            ROOT = instance.ROOT;
            filesMap.putAll(instance.filesMap);
            credentialsMap.putAll(instance.credentialsMap);
        }
    }

    @PostConstruct
    private void loadInitialResources() {
        List<Resource> resources = new ArrayList<>();
        Iterable<VirtualFile> virtualFiles = fileRepository.findAll();
        for (VirtualFile vf : virtualFiles) {
            resources.add(new VirtualFileResource(new VirtualFileInputStream(vf), instance.ROOT));
        }
        instance.ROOT.setChildren(resources);
    }

    public static Map<String,String> getCredentialsMap() {
        return instance != null ? instance.credentialsMap : new HashMap<>();
    }

    public static VirtualResourceFactory getInstance() {
        return instance;
    }

    private void addUser(String username, String password) {
        credentialsMap.put(username, password);
    }


    @Override
    public Resource getResource(String host, String url) {
        log.debug("getResource: url: " + url);
        
        // Strip /webdav prefix if present since our ROOT represents the webdav folder
        String adjustedUrl = url;
        if (url.startsWith("/webdav/")) {
            adjustedUrl = url.substring("/webdav".length());
        } else if (url.equals("/webdav")) {
            adjustedUrl = "/";
        }
        
        Path path = Path.path(adjustedUrl);
        Resource r = find(path);
        log.debug("_found: " + r + " for url: " + url + " (adjusted: " + adjustedUrl + ") and path: " + path);
        return r;
    }

    private Resource find(Path path) {
        if (path.isRoot()) {
            return ROOT;
        }
        Resource rParent = find(path.getParent());
        if (rParent == null) {
            return null;
        }
        if (rParent instanceof VirtualFolderResource) {
            VirtualFolderResource folder = (VirtualFolderResource) rParent;
            for (Resource rChild : folder.getChildren()) {
                if (rChild.getName().equals(path.getName())) {
                    return rChild;
                }
            }
            log.warn("Resource: " + path.getName() + " not found in collection: " + path.getParent() + " of type: " + rParent.getClass());
        } else {
            log.warn("parent not found: " + path.getParent());
        }
        return null;
    }
}