package com.supermarket.config;

import com.supermarket.model.InventoryItem;
import com.supermarket.repository.InventoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Seeds the catalog at startup with {@code catalog.size} items (default 2000),
 * each with {@code stock-per-item} units (default 10000). Idempotent: it only
 * seeds when the items table is empty, so restarts against a persistent
 * database preserve current stock. Values mirror the reference mock server so
 * SKUs/prices are comparable across weeks.
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final InventoryRepository inventoryRepository;
    private final int catalogSize;
    private final int stockPerItem;

    public DataInitializer(InventoryRepository inventoryRepository,
                           @Value("${supermarket.catalog.size:2000}") int catalogSize,
                           @Value("${supermarket.catalog.stock-per-item:10000}") int stockPerItem) {
        this.inventoryRepository = inventoryRepository;
        this.catalogSize = catalogSize;
        this.stockPerItem = stockPerItem;
    }

    @Override
    public void run(String... args) {
        long existing = inventoryRepository.count();
        if (existing > 0) {
            log.info("Catalog already seeded ({} items); skipping initialization.", existing);
            return;
        }

        List<InventoryItem> items = new ArrayList<>(catalogSize);
        for (int i = 1; i <= catalogSize; i++) {
            String sku = "SKU-" + String.format("%06d", i);
            String name = "Item " + i;
            double price = Math.round((0.5 + (i % 47) * 0.35) * 100.0) / 100.0;
            items.add(new InventoryItem(sku, name, price, stockPerItem));
        }
        inventoryRepository.saveAll(items);
        log.info("Seeded catalog: {} items, {} units each.", catalogSize, stockPerItem);
    }
}
