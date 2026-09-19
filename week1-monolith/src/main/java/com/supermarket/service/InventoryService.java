package com.supermarket.service;

import com.supermarket.dto.Dtos.LowStockAlert;
import com.supermarket.dto.Dtos.LowStockResponse;
import com.supermarket.exception.ApiExceptions.NotFoundException;
import com.supermarket.model.InventoryItem;
import com.supermarket.repository.InventoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns all stock mutations and low-stock reporting. The decrement path is the
 * heart of the correctness invariant: it runs under a PESSIMISTIC_WRITE row
 * lock so concurrent completions of the same SKU serialize at the database and
 * can never both consume the same unit.
 */
@Service
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final int defaultThreshold;

    public InventoryService(InventoryRepository inventoryRepository,
                            @Value("${supermarket.inventory.low-stock-threshold:50}") int defaultThreshold) {
        this.inventoryRepository = inventoryRepository;
        this.defaultThreshold = defaultThreshold;
    }

    public int defaultThreshold() {
        return defaultThreshold;
    }

    /**
     * Decrement {@code quantity} units of {@code sku}, holding a write lock on
     * the row. Must be called inside the caller's transaction (it is, from
     * {@link TransactionService#complete}) so the lock is held until commit.
     * Stock is clamped at zero so it can never go negative.
     */
    public void decrement(String sku, int quantity) {
        InventoryItem item = inventoryRepository.findBySkuForUpdate(sku)
                .orElseThrow(() -> new NotFoundException("UNKNOWN_SKU", "No such SKU: " + sku));
        int newStock = Math.max(0, item.getStock() - quantity);
        item.setStock(newStock);
        // Managed entity — flushed with the surrounding transaction.
    }

    @Transactional(readOnly = true)
    public LowStockResponse lowStock(Integer thresholdOverride) {
        int threshold = thresholdOverride != null ? thresholdOverride : defaultThreshold;
        List<InventoryItem> low = inventoryRepository.findByStockLessThanOrderBySkuAsc(threshold);
        String now = Instant.now().toString();
        List<LowStockAlert> alerts = new ArrayList<>(low.size());
        for (InventoryItem item : low) {
            alerts.add(new LowStockAlert(item.getSku(), item.getName(), item.getStock(), threshold, now));
        }
        return new LowStockResponse(threshold, now, alerts);
    }
}
