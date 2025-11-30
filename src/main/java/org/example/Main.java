package org.example;

import io.milton.servlet.MiltonFilter;
import jakarta.servlet.DispatcherType;
import org.NzbUtils;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.example.webdav.VirtualFile;
import org.example.webdav.VirtualWebDavFactory;
import org.model.Nzb;
import org.parser.NzbParserFactory;
import org.service.UsenetDownloadService;

import java.util.EnumSet;

public class Main {
    public static void main(String[] args) throws Exception {
        // Create the VirtualWebDav factory
        VirtualWebDavFactory factory = new VirtualWebDavFactory();
        Nzb nzb = NzbParserFactory.createParser().parse(Main.class.getResourceAsStream("/sample3.nzb"));
        VirtualFile vf = new VirtualFile(nzb.getFile().get(3).getTotalBytes(), NzbUtils.sanitizeFileName(nzb.getFile().get(3).getSubject()), nzb.getFile().get(3));
        VirtualWebDavFactory.addFile(vf.filename(), vf);


        // 1. Create Jetty server on port 8080
        Server server = new Server(8080);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        // Create FilterHolder and set the filter instance
        FilterHolder miltonFilterHolder = new FilterHolder(new MiltonFilter());

        // Set the ResourceFactory class for Milton
        miltonFilterHolder.setInitParameter(
                "resource.factory.class",
                "org.example.webdav.VirtualWebDavFactory" // fully-qualified class name
        );

        // Optional: enable debug
        miltonFilterHolder.setInitParameter("milton.debug", "true");

        // Map filter to all requests
        context.addFilter(miltonFilterHolder, "/*", EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));

        server.setHandler(context);
        server.start();
        System.out.println("WebDAV server running at http://localhost:8080");
        server.join();
    }
}
