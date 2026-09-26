package com.supermarket.transaction.catalog;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code supermarket.catalog.*}. Values mirror the reference mock server. */
@ConfigurationProperties(prefix = "supermarket.catalog")
public record CatalogProperties(int size, int stockPerItem) {

    public CatalogProperties {
        if (size <= 0) {
            size = 2000;
        }
        if (stockPerItem <= 0) {
            stockPerItem = 10000;
        }
    }
}
