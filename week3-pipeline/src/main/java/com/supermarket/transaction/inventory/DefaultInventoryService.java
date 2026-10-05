package com.supermarket.transaction.inventory;

import com.supermarket.domain.ItemSnapshot;
import com.supermarket.persistence.ItemDao;
import com.supermarket.transaction.inventory.model.LowStockAlertView;
import com.supermarket.transaction.inventory.model.LowStockView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    public Optional<ItemSnapshot> findItem(String sku) {
        return itemDao.findBySku(sku);
    }

    /**
     * {@code MANDATORY} is the fix for a real week-1 hazard: the decrement had
     * no transaction annotation at all and simply assumed the caller had opened
     * one. If that assumption had ever broken, the PESSIMISTIC_WRITE lock would
     * have been released at once and the oversell bug would have reappeared
     * silently. Now the call fails loudly instead.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void decrement(String sku, int quantity) {
        itemDao.decrementStockForUpdate(sku, quantity);
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
