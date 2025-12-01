package org.example.webdav;

import io.milton.http.Auth;
import io.milton.http.Range;
import io.milton.http.Request;
import io.milton.http.http11.auth.DigestGenerator;
import io.milton.http.http11.auth.DigestResponse;
import io.milton.resource.DigestResource;
import io.milton.resource.Resource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * BM: added reportable so that all these resource classes work with REPORT
 *
 * @author alex
 */
public class AbstractResource implements Resource, DigestResource {

    private static Logger log = LogManager.getLogger(AbstractResource.class);
    protected UUID id;
    protected String name;
    protected Date modDate;
    protected Date createdDate;
    protected TFolderResource parent;


    public AbstractResource(TFolderResource parent, String name) {
        id = UUID.randomUUID();
        this.parent = parent;
        this.name = name;
        modDate = new Date();
        createdDate = new Date();
        if (parent != null) {
            checkAndRemove(parent, name);
            parent.children.add(this);
        }
    }


    @Override
    public Object authenticate(String user, String requestedPassword) {
        return "authenticated";
//        String p = VirtualWebDavFactory.credentialsMap.get(user);
//        if (p != null) {
//            if (p.equals(requestedPassword)) {
//                return Boolean.TRUE;
//            } else {
//                log.warn("that password is incorrect. Try:" + p);
//            }
//        } else {
//            log.warn("user not found: " + user + " - try 'userA'");
//        }
//        return null;

    }

    @Override
    public Object authenticate(DigestResponse digestRequest) {
        String p = VirtualWebDavFactory.credentialsMap.get(digestRequest.getUser());
        if (p != null) {
            DigestGenerator gen = new DigestGenerator();
            String actual = gen.generateDigest(digestRequest, p);
            if (actual.equals(digestRequest.getResponseDigest())) {
                return p;
            } else {
                log.warn("that password is incorrect. Try 'password'");
            }
        } else {
            log.warn("user not found: " + digestRequest.getUser() + " - try 'userA'");
        }
        return null;

    }

    @Override
    public String getUniqueId() {
        return this.id.toString();
    }

    @Override
    public String checkRedirect(Request request) {
        return null;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean authorise(Request request, Request.Method method, Auth auth) {
        log.debug("authorise");
        if (getName().matches(".*\\.(mkv|mp4|avi|mov)$")) {
            log.debug("Allowing anonymous access for media file: " + getName());
            return true;
        }
        return auth != null;
    }

    @Override
    public String getRealm() {
        return "testrealm";
    }

    @Override
    public Date getModifiedDate() {
        return modDate;
    }

    private void checkAndRemove(TFolderResource parent, String name) {
        TResource r = (TResource) parent.child(name);
        if (r != null) {
            parent.children.remove(r);
        }
    }

    @Override
    public boolean isDigestAllowed() {
        return true;
    }
}