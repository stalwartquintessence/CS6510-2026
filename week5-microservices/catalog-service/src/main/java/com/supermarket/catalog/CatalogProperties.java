package com.supermarket.catalog;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code supermarket.catalog.*}. Values mirror the reference mock server. The
 * initial stock per item moved to the inventory service with the stock itself.
 */
@ConfigurationProperties(prefix = "supermarket.catalog")
public record CatalogProperties(int size) {

    public CatalogProperties {
        if (size <= 0) {
            size = 2000;
        }
    }
}
