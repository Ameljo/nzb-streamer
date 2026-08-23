package org.nzbstreamer.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories("org.nzbstreamer.repository")
public class PersistenceConfig {
}
