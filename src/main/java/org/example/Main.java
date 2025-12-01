package org.example;

import io.milton.servlet.MiltonFilter;
import jakarta.servlet.DispatcherType;
import org.NzbUtils;
import org.apache.commons.logging.Log;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Slf4jRequestLogWriter;
import org.example.webdav.VirtualFile;
import org.example.webdav.VirtualWebDavFactory;
import org.model.Nzb;
import org.parser.NzbParserFactory;

import java.net.URL;
import java.util.EnumSet;
import java.util.Enumeration;

public class Main {
    private static Logger logger = LogManager.getLogger(Main.class.getName());
    public static void main(String[] args) throws Exception {
//        ClassLoader cl = Thread.currentThread().getContextClassLoader();
//        Enumeration<URL> urls = cl.getResources("org/slf4j/impl/StaticLoggerBinder.class");
//        while (urls.hasMoreElements()) {
//            System.out.println("SLF4J binder: " + urls.nextElement());
//        }
        // Create the VirtualWebDav factory
        VirtualWebDavFactory factory = new VirtualWebDavFactory();
        Nzb nzb = NzbParserFactory.createParser().parse(Main.class.getResourceAsStream("/sample3.nzb"));
        VirtualFile vf = new VirtualFile(nzb.getFile().get(3).getTotalBytes(), NzbUtils.sanitizeFileName(nzb.getFile().get(3).getSubject()), nzb.getFile().get(3));
        VirtualWebDavFactory.addFile(vf.filename(), vf);


        // 1. Create Jetty server on port 8080
        Server server = new Server(8080);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        CustomRequestLog requestLog = new CustomRequestLog(new Slf4jRequestLogWriter(), CustomRequestLog.EXTENDED_NCSA_FORMAT);

//        RequestLogHandler logHandler = new RequestLogHandler();
//        RequestLo
//        logHandler.setRequestLog(requestLog);



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
