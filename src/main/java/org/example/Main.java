package org.example;

import org.apache.jackrabbit.commons.JcrUtils;
import org.apache.jackrabbit.webdav.simple.SimpleWebdavServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.apache.jackrabbit.core.TransientRepository;

import javax.jcr.Repository;
import java.io.File;

public class Main {
    public static void main(String[] args) throws Exception {
        File repoHome = new File("jackrabbit-repo");
        System.setProperty("org.apache.jackrabbit.repository.home", repoHome.getAbsolutePath());
//        TransientRepository repository = new TransientRepository(repoHome);
        Repository repository = JcrUtils.getRepository();
        // Start Jetty server
        Server server = new Server(8080);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        server.setHandler(context);
        // Add WebDAV servlet (use existing repository instance)
        ServletHolder holder = new ServletHolder(new SimpleWebdavServlet() {
            @Override
            public Repository getRepository() {
                return repository;
            }
        });
        holder.setInitParameter(SimpleWebdavServlet.INIT_PARAM_RESOURCE_PATH_PREFIX, "/webdav");
        context.addServlet(holder, "/webdav/*");

        server.start();
        System.out.println("WebDAV server started on http://localhost:8080/webdav");
        server.join();
    }
}
