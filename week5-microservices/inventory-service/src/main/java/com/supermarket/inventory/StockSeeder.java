package com.supermarket.inventory;

import com.supermarket.contracts.CatalogItem;
import com.supermarket.contracts.CatalogServiceGrpc;
import com.supermarket.contracts.ListItemsRequest;
import com.supermarket.inventory.model.StockItem;
import com.supermarket.support.rpc.StubPool;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Seeds stock on an empty inventory database: one row per catalog item, with the
 * configured initial stock and a copy of the item's name.
 *
 * <p>In week 4 the catalog seeded {@code items.stock} itself, because stock was a
 * column of the catalog's row. The inventory service now owns stock and has no access
 * to the catalog's database, so it asks the catalog service, once, for the item list.
 * The catalog seeds in one transaction, so the answer is either empty (not seeded yet:
 * wait and ask again) or complete.
 */
@Component
class StockSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(StockSeeder.class);

    private final InventoryService inventoryService;
    private final StubPool<CatalogServiceGrpc.CatalogServiceBlockingStub> catalog;
    private final InventoryProperties properties;
    private final InventoryRpcProperties rpc;

    StockSeeder(InventoryService inventoryService,
                StubPool<CatalogServiceGrpc.CatalogServiceBlockingStub> catalog,
                InventoryProperties properties,
                InventoryRpcProperties rpc) {
        this.inventoryService = inventoryService;
        this.catalog = catalog;
        this.properties = properties;
        this.rpc = rpc;
    }

    @Override
    public void run(String... args) throws InterruptedException {
        long existing = inventoryService.stockedItems();
        if (existing > 0) {
            log.info("Stock already seeded ({} items); skipping initialization.", existing);
            return;
        }
        List<CatalogItem> items = fetchCatalog();
        List<StockItem> rows = new ArrayList<>(items.size());
        for (CatalogItem item : items) {
            rows.add(new StockItem(item.getSku(), item.getName(), properties.initialStock()));
        }
        inventoryService.seed(rows);
        log.info("Seeded stock: {} items, {} units each.", rows.size(), properties.initialStock());
    }

    private List<CatalogItem> fetchCatalog() throws InterruptedException {
        long giveUpAt = System.nanoTime() + properties.seedTimeout().toNanos();
        while (true) {
            try {
                List<CatalogItem> items = catalog.next()
                        .withDeadlineAfter(rpc.deadline().toMillis(), TimeUnit.MILLISECONDS)
                        .listItems(ListItemsRequest.getDefaultInstance())
                        .getItemsList();
                if (!items.isEmpty()) {
                    return items;
                }
                log.info("Catalog is empty (still seeding?); retrying");
            } catch (StatusRuntimeException e) {
                log.info("Catalog service not reachable yet ({}); retrying", e.getStatus().getCode());
            }
            if (System.nanoTime() > giveUpAt) {
                throw new IllegalStateException("No catalog to seed stock from after " + properties.seedTimeout());
            }
            Thread.sleep(1_000);
        }
    }
}
