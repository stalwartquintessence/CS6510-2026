package com.supermarket.config;

import org.springframework.context.annotation.Configuration;

/**
 * Marker configuration for the persistence layer. Datasource, JPA and
 * connection-pool settings live in application.properties; Spring Boot's
 * auto-configuration wires them from there. Kept as an explicit class so the
 * package structure matches the assignment (com.supermarket.config).
 */
@Configuration
public class DatabaseConfig {
}
