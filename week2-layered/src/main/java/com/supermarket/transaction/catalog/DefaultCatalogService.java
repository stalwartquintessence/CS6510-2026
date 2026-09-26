package com.supermarket.transaction.catalog;

import com.supermarket.domain.ItemSnapshot;
import com.supermarket.domain.Money;
import com.supermarket.persistence.ItemDao;
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
    public List<ItemSnapshot> catalog() {
        return itemDao.findAllOrderBySku();
    }

    @Override
    @Transactional
    public void seedIfEmpty(int size, int stockPerItem) {
        long existing = itemDao.count();
        if (existing > 0) {
            log.info("Catalog already seeded ({} items); skipping initialization.", existing);
            return;
        }

        List<ItemSnapshot> items = new ArrayList<>(size);
        for (int i = 1; i <= size; i++) {
            String sku = "SKU-" + String.format("%06d", i);
            String name = "Item " + i;
            double price = Money.round2(0.5 + (i % 47) * 0.35);
            items.add(new ItemSnapshot(sku, name, price, stockPerItem));
        }
        itemDao.saveAll(items);
        log.info("Seeded catalog: {} items, {} units each.", size, stockPerItem);
    }
}
