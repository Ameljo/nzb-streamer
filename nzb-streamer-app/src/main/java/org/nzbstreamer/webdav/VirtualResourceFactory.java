package org.nzbstreamer.webdav;


import io.milton.http.ResourceFactory;
import io.milton.resource.Resource;
import jakarta.annotation.PostConstruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.service.VirtualResourceService;
import org.springframework.beans.BeansException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "webdav")
public class VirtualResourceFactory implements ResourceFactory, ApplicationContextAware {

    private static final Logger log = LogManager.getLogger(VirtualResourceFactory.class);
    private static VirtualResourceFactory instance;

    private VirtualResourceService virtualResourceService;
    private Map<String, String> users = new HashMap<>();

    final Map<String,String> credentialsMap = new HashMap<>();

    public VirtualResourceFactory() {
        if (instance == null) {
            instance = this;
            log.info("VirtualWebDavFactory: Created new instance");
        } else {
            log.info("VirtualWebDavFactory: Reusing existing singleton instance");
            credentialsMap.putAll(instance.credentialsMap);
        }
    }

    public void setUsers(Map<String, String> users) {
        this.users = users;
    }

    @PostConstruct
    private void init() {
        credentialsMap.putAll(users);
    }

    private void addUser(String username, String password) {
        credentialsMap.put(username, password);
    }


    @Override
    public Resource getResource(String host, String url) {
        return virtualResourceService.getResource(host, url);
    }


    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        instance.virtualResourceService = applicationContext.getBean(VirtualResourceService.class);
    }
}