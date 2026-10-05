package com.supermarket.transaction.catalog;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds the catalog at startup.
 *
 * <p>Week 1's equivalent lived in {@code config/} and wrote entities straight
 * through the repository, bypassing the service layer entirely. Here the
 * startup hook only triggers; the catalog module still owns the work.
 */
@Component
class CatalogSeeder implements CommandLineRunner {

    private final CatalogService catalogService;
    private final CatalogProperties properties;

    CatalogSeeder(CatalogService catalogService, CatalogProperties properties) {
        this.catalogService = catalogService;
        this.properties = properties;
    }

    @Override
    public void run(String... args) {
        catalogService.seedIfEmpty(properties.size(), properties.stockPerItem());
    }
}
