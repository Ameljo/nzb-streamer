package org.example.webdav;


import io.milton.common.Path;
import io.milton.http.ResourceFactory;
import io.milton.resource.Resource;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class VirtualWebDavFactory implements ResourceFactory {

    private static final org.apache.log4j.Logger log = org.apache.log4j.Logger.getLogger(VirtualWebDavFactory.class);
    public static final TFolderResource ROOT = new TFolderResource((TFolderResource) null, "http://localhost:8080");
    static final Map<String,VirtualFile> filesMap = new HashMap<String, VirtualFile>();
    static final Map<String,String> credentialsMap = new HashMap<String, String>();

    static {
        addUser( "usera", "password");
        addUser( "userb", "password");
        addUser( "userv", "password");
    }
    private static void addUser(String username, String password) {
        credentialsMap.put(username, password);
    }

    public static void addFile(String name, VirtualFile file) throws IOException {
        ROOT.createNew(name, new OnDemandNzbInputStream(file), file.getSize(), "video/mkv");
    }


    @Override
    public Resource getResource(String host, String url) {
        log.debug("getResource: url: " + url);
        Path path = Path.path(url);
        Resource r = find(path);
        log.debug("_found: " + r + " for url: " + url + " and path: " + path);
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
        if (rParent instanceof TFolderResource) {
            TFolderResource folder = (TFolderResource) rParent;
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