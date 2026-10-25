package com.supermarket.inventory.persistence;

import com.supermarket.inventory.model.StockItem;
import com.supermarket.support.error.ItemNotFoundException;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
class JpaStockDao implements StockDao {

    private final StockItemJpaRepository repository;

    JpaStockDao(StockItemJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<StockItem> findBelowStock(int threshold) {
        return repository.findByStockLessThanOrderBySkuAsc(threshold).stream()
                .map(JpaStockDao::toStockItem)
                .toList();
    }

    @Override
    public int decrementForUpdate(String sku, int quantity) {
        StockItemEntity item = lock(sku);
        int taken = Math.max(0, Math.min(quantity, item.getStock()));
        item.setStock(item.getStock() - taken);
        // Managed entity — flushed with the caller's transaction, which is also
        // what holds the row lock until commit.
        return taken;
    }

    @Override
    public void incrementForUpdate(String sku, int quantity) {
        StockItemEntity item = lock(sku);
        item.setStock(item.getStock() + quantity);
    }

    @Override
    public long count() {
        return repository.count();
    }

    @Override
    public void saveAll(List<StockItem> items) {
        List<StockItemEntity> entities = new ArrayList<>(items.size());
        for (StockItem item : items) {
            entities.add(new StockItemEntity(item.sku(), item.name(), item.stock()));
        }
        repository.saveAll(entities);
    }

    private StockItemEntity lock(String sku) {
        return repository.findBySkuForUpdate(sku).orElseThrow(() -> new ItemNotFoundException(sku));
    }

    private static StockItem toStockItem(StockItemEntity item) {
        return new StockItem(item.getSku(), item.getName(), item.getStock());
    }
}
