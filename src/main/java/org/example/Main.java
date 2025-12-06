package org.example;

import io.milton.servlet.MiltonFilter;
import jakarta.servlet.DispatcherType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Slf4jRequestLogWriter;
import org.webdav.VirtualFile;
import org.webdav.VirtualWebDavFactory;
import org.model.Nzb;
import org.parser.NzbParserFactory;
import org.transformers.NzbFileToVirtualFileTransformer;
import org.transformers.NzbFileTransformer;

import java.util.EnumSet;

public class Main {
    private static Logger logger = LogManager.getLogger(Main.class.getName());
    private static final String SERVER = "YOUR_USENET_SERVER";
    private static final int PORT = 119;
    private static final String USERNAME = "YOUR_USENET_USERNAME";
    private static final String PASSWORD = "YOUR_USENET_PASSWORD";

    public static void main(String[] args) throws Exception {
        // Create the VirtualWebDav factory
        Nzb nzb = NzbParserFactory.createParser().parse(Main.class.getResourceAsStream("/sample3.nzb"));
        NzbFileTransformer<VirtualFile> transformer = new NzbFileToVirtualFileTransformer();
        VirtualFile vf = transformer.transform(nzb.getFile(3));
        VirtualWebDavFactory.addFile(vf.filename(), vf);


        // 1. Create Jetty server on port 8080
        Server server = new Server(8080);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        CustomRequestLog requestLog = new CustomRequestLog(new Slf4jRequestLogWriter(), CustomRequestLog.EXTENDED_NCSA_FORMAT);

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
        logger.info("WebDAV server running at http://localhost:8080");
        server.join();
    }
}
