package org.servlet;

import org.apache.jackrabbit.webdav.*;
import org.apache.jackrabbit.webdav.server.AbstractWebdavServlet;

public class WebdavServlet extends AbstractWebdavServlet {
    private volatile DavSessionProvider davSessionProvider;
    private volatile DavLocatorFactory locatorFactory;
    private volatile DavResourceFactory resourceFactory;

    @Override
    protected boolean isPreconditionValid(WebdavRequest webdavRequest, DavResource davResource) {
        return false;
    }

    @Override
    public DavSessionProvider getDavSessionProvider() {
        return davSessionProvider;
    }

    @Override
    public void setDavSessionProvider(DavSessionProvider davSessionProvider) {
        this.davSessionProvider = davSessionProvider;
    }

    @Override
    public DavLocatorFactory getLocatorFactory() {
        return locatorFactory;
    }

    @Override
    public void setLocatorFactory(DavLocatorFactory davLocatorFactory) {
        this.locatorFactory = davLocatorFactory;
    }

    @Override
    public DavResourceFactory getResourceFactory() {
        return resourceFactory;
    }

    @Override
    public void setResourceFactory(DavResourceFactory davResourceFactory) {
        this.resourceFactory = davResourceFactory;
    }
}
