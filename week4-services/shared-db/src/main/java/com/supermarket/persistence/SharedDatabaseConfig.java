package com.supermarket.persistence;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The single hook a service uses to link the shared database access library:
 * {@code @Import(SharedDatabaseConfig.class)}. Services scan only their own
 * packages, so nothing here is picked up by accident, and every service sees
 * exactly the same entities, DAOs and SQL.
 */
@Configuration(proxyBeanMethods = false)
@EntityScan("com.supermarket.persistence.entity")
@EnableJpaRepositories("com.supermarket.persistence.jpa")
@ComponentScan("com.supermarket.persistence.jpa")
public class SharedDatabaseConfig {
}
