package org.webdav;

import io.milton.http.*;
import io.milton.http.webdav.PropPatchHandler.Fields;
import io.milton.resource.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.PrintWriter;
import java.util.Date;
import java.util.UUID;

public abstract class TResource extends AbstractResource implements GetableResource, PropFindableResource, DeletableResource, MoveableResource,
        CopyableResource, DigestResource, LockableResource {

    private static Logger log = LogManager.getLogger(TResource.class);
    private LockToken currentLock;

    public TResource(TFolderResource parent, String name) {
        super(parent, name);
    }

    protected abstract Object clone(TFolderResource newParent, String newName);


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
    public void moveTo(CollectionResource rDest, String name) {
        log.debug("moving..");
        TFolderResource d = (TFolderResource) rDest;
        this.parent.children.remove(this);
        this.parent = d;
        this.parent.children.add(this);
        this.name = name;
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

    @Override
    public void copyTo(CollectionResource toCollection, String destName) {
        System.out.println("COPY: " + parent.name + "/" + this.name + " --->>>" + toCollection.getName() + "/" + destName);
        TResource rClone;
        rClone = (TResource) this.clone((TFolderResource) toCollection, destName);
        rClone.name = destName;
    }

    public int compareTo(Resource o) {
        if (o instanceof TResource res) {
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