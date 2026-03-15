package org.nzbstreamer.webdav;


import io.milton.http.ResourceFactory;
import io.milton.resource.Resource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.service.VirtualResourceService;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class VirtualResourceFactory implements ResourceFactory, ApplicationContextAware {

    private static final Logger log = LogManager.getLogger(VirtualResourceFactory.class);
    private static VirtualResourceFactory instance;

    private VirtualResourceService virtualResourceService;

    final Map<String,String> credentialsMap = new HashMap<>();

    public VirtualResourceFactory() {
        // Implement singleton pattern - if instance already exists, reuse its data
        if (instance == null) {
            instance = this;
            addUser("usera", "password");
            addUser("userb", "password");
            addUser("userv", "password");
            log.info("VirtualWebDavFactory: Created new instance");
        } else {
            // Milton is creating a second instance, redirect to the singleton
            log.info("VirtualWebDavFactory: Reusing existing singleton instance");
            credentialsMap.putAll(instance.credentialsMap);
        }
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