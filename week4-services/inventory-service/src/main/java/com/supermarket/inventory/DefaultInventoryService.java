package com.supermarket.inventory;

import com.supermarket.domain.ItemSnapshot;
import com.supermarket.inventory.model.LowStockAlertView;
import com.supermarket.inventory.model.LowStockView;
import com.supermarket.persistence.ItemDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
class DefaultInventoryService implements InventoryService {

    private final ItemDao itemDao;
    private final InventoryProperties properties;

    DefaultInventoryService(ItemDao itemDao, InventoryProperties properties) {
        this.itemDao = itemDao;
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
        List<ItemSnapshot> low = itemDao.findBelowStock(threshold);
        List<LowStockAlertView> alerts = new ArrayList<>(low.size());
        for (ItemSnapshot item : low) {
            alerts.add(new LowStockAlertView(item.sku(), item.name(), item.stock(), threshold));
        }
        return new LowStockView(threshold, Instant.now(), alerts);
    }
}
