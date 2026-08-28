package org.nzbstreamer.webdav;

import io.milton.http.*;
import io.milton.http.http11.auth.DigestResponse;
import io.milton.http.webdav.PropPatchHandler.Fields;
import io.milton.resource.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.PrintWriter;
import java.util.Date;
import java.util.UUID;

public abstract class AbstractResource implements GetableResource, PropFindableResource, DeletableResource, LockableResource, DigestResource {

    private static Logger log = LogManager.getLogger(AbstractResource.class);
    private LockToken currentLock;
    protected VirtualFolderResource parent;
    protected String name;
    protected UUID id;
    protected Date modDate;
    protected Date createdDate;

    public AbstractResource(VirtualFolderResource parent, String name) {
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

    protected abstract Object clone(VirtualFolderResource newParent, String newName);




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
//        String p = VirtualResourceFactory.getCredentialsMap().get(digestRequest.getUser());
//        if (p != null) {
//            DigestGenerator gen = new DigestGenerator();
//            String actual = gen.generateDigest(digestRequest, p);
//            if (actual.equals(digestRequest.getResponseDigest())) {
//                return p;
//            } else {
//                log.warn("that password is incorrect. Try 'password'");
//            }
//        } else {
//            log.warn("user not found: " + digestRequest.getUser() + " - try 'userA'");
//        }
        return digestRequest;

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

    private void checkAndRemove(VirtualFolderResource parent, String name) {
        AbstractResource r = (AbstractResource) parent.child(name);
        if (r != null) {
            parent.children.remove(r);
        }
    }

    public String getHref() {
        if (parent == null) {
            return "";
        } else {
            String s = parent.getHref();
            if (!s.endsWith("/")) {
                s = s + "/";
            }
            s = s + name;
            if (this instanceof CollectionResource) {
                s = s + "/";
            }
            return s;
        }
    }

    @Override
    public Long getContentLength() {
        return null;
    }

    @Override
    public Long getMaxAgeSeconds(Auth auth) {
        return (long) 10;
    }


    @Override
    public Date getCreateDate() {
        return createdDate;
    }

    @Override
    public void delete() {
        if (this.parent == null) {
            throw new RuntimeException("attempt to delete root");
        }

        if (this.parent.children == null) {
            throw new NullPointerException("children is null");
        }
        this.parent.children.remove(this);
    }

    public int compareTo(Resource o) {
        if (o instanceof AbstractResource res) {
            return this.getName().compareTo(res.getName());
        } else {
            return -1;
        }
    }

    /**
     * This is required for the PropPatchableResource interface, but should not
     * be implemented.
     *
     * Implement CustomPropertyResource or MultiNamespaceCustomPropertyResource
     * instead
     *
     * @param fields
     */
    public void setProperties(Fields fields) {
    }

    protected void print(PrintWriter printer, String s) {
        printer.print(s);
    }

    @Override
    public final LockResult lock(LockTimeout lockTimeout, LockInfo lockInfo) {
        log.trace("Lock : {} info : {} on resource : {} in : {}", lockTimeout, lockInfo, getName(), parent);
        LockToken token = new LockToken();
        token.info = lockInfo;
        token.timeout = LockTimeout.parseTimeout("30");
        token.tokenId = UUID.randomUUID().toString();
        currentLock = token;
        return LockResult.success(token);
    }

    @Override
    public final LockResult refreshLock(String tokenId, LockTimeout timeout) {
        log.trace("RefreshLock : {} on resource : {} in : {}", tokenId, getName(), parent);
        //throw new UnsupportedOperationException("Not supported yet.");
        LockToken token = new LockToken();
        token.info = null;
        token.timeout = timeout;
        token.tokenId = currentLock.tokenId;
        currentLock = token;
        return LockResult.success(token);
    }

    @Override
    public void unlock(String arg0) {
        log.trace("UnLock : {} on resource : {} in : {}", arg0, getName(), parent);
        currentLock = null;
        //throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public final LockToken getCurrentLock() {
        log.trace("GetCurrentLock");
        return currentLock;
    }

    @Override
    public boolean isDigestAllowed() {
        return true;
    }

}