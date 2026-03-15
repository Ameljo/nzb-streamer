package org.nzbstreamer.config;

import io.milton.servlet.MiltonFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MiltonConfig {

    @Bean
    public FilterRegistrationBean<MiltonFilter> miltonFilter() {
        FilterRegistrationBean<MiltonFilter> registration = new FilterRegistrationBean<>();
        MiltonFilter filter = new MiltonFilter();

        registration.setFilter(filter);
        registration.addUrlPatterns("/webdav/*");
        registration.setOrder(1);

        // Configure Milton to use our resource factory class
        registration.addInitParameter("resource.factory.class", "org.nzbstreamer.webdav.VirtualResourceFactory");

        return registration;
    }
}

