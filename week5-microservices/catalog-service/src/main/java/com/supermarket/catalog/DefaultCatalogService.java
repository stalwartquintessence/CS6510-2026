package com.supermarket.catalog;

import com.supermarket.catalog.model.ItemView;
import com.supermarket.catalog.persistence.ItemDao;
import com.supermarket.support.error.ItemNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
class DefaultCatalogService implements CatalogService {

    private static final Logger log = LoggerFactory.getLogger(DefaultCatalogService.class);

    private final ItemDao itemDao;

    DefaultCatalogService(ItemDao itemDao) {
        this.itemDao = itemDao;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ItemView> catalog() {
        return itemDao.findAllOrderBySku();
    }

    @Override
    @Transactional(readOnly = true)
    public ItemView item(String sku) {
        return itemDao.findBySku(sku).orElseThrow(() -> new ItemNotFoundException(sku));
    }

    /**
     * One transaction, so another service listing the catalog sees either none of it
     * or all of it — the inventory service relies on that to seed its stock rows.
     * Same SKUs, names and prices as weeks 1–4, so rankings stay comparable.
     */
    @Override
    @Transactional
    public void seedIfEmpty(int size) {
        long existing = itemDao.count();
        if (existing > 0) {
            log.info("Catalog already seeded ({} items); skipping initialization.", existing);
            return;
        }

        List<ItemView> items = new ArrayList<>(size);
        for (int i = 1; i <= size; i++) {
            String sku = "SKU-" + String.format("%06d", i);
            String name = "Item " + i;
            double price = round2(0.5 + (i % 47) * 0.35);
            items.add(new ItemView(sku, name, price));
        }
        itemDao.saveAll(items);
        log.info("Seeded catalog: {} items.", size);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
