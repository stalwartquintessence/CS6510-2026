package com.supermarket.catalog;

import com.supermarket.support.GrpcServerConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/**
 * Week 5 catalog service: serves the item list and single items, and seeds them on
 * first run. Owns the catalog database (its {@code persistence} package is the only
 * code that can reach it) and calls no other service: it is the leaf of the call graph.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@Import(GrpcServerConfig.class)
public class CatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }
}
