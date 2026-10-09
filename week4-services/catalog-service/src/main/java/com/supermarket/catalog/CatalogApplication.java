package com.supermarket.catalog;

import com.supermarket.persistence.SharedDatabaseConfig;
import com.supermarket.support.GrpcServerConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/**
 * Week 4 catalog service: serves the item list and seeds it on first run. Reaches
 * the database only through the shared {@code ItemDao}; reaches no other service.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@Import({SharedDatabaseConfig.class, GrpcServerConfig.class})
public class CatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }
}
