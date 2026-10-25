package com.supermarket.inventory;

import com.supermarket.inventory.model.LowStockAlertView;
import com.supermarket.inventory.model.LowStockView;
import com.supermarket.inventory.model.StockItem;
import com.supermarket.inventory.persistence.StockDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
class DefaultInventoryService implements InventoryService {

    private final StockDao stockDao;
    private final InventoryProperties properties;

    DefaultInventoryService(StockDao stockDao, InventoryProperties properties) {
        this.stockDao = stockDao;
        this.properties = properties;
    }

    @Override
    public int defaultThreshold() {
        return properties.lowStockThreshold();
    }

    @Override
    @Transactional(readOnly = true)
    public LowStockView lowStock(Integer thresholdOverride) {
        int threshold = thresholdOverride != null ? thresholdOverride : defaultThreshold();
        List<StockItem> low = stockDao.findBelowStock(threshold);
        List<LowStockAlertView> alerts = new ArrayList<>(low.size());
        for (StockItem item : low) {
            alerts.add(new LowStockAlertView(item.sku(), item.name(), item.stock(), threshold));
        }
        return new LowStockView(threshold, Instant.now(), alerts);
    }

    @Override
    @Transactional(readOnly = true)
    public long stockedItems() {
        return stockDao.count();
    }

    @Override
    @Transactional
    public void seed(List<StockItem> items) {
        stockDao.saveAll(items);
    }
}
