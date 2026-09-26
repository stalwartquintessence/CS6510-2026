package com.supermarket.persistence.jpa;

import com.supermarket.domain.ItemSnapshot;
import com.supermarket.domain.error.ItemNotFoundException;
import com.supermarket.persistence.ItemDao;
import com.supermarket.persistence.entity.InventoryItem;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
class JpaItemDao implements ItemDao {

    private final InventoryJpaRepository repository;

    JpaItemDao(InventoryJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<ItemSnapshot> findAllOrderBySku() {
        return repository.findAll(Sort.by("sku")).stream()
                .map(JpaItemDao::toSnapshot)
                .toList();
    }

    @Override
    public Optional<ItemSnapshot> findBySku(String sku) {
        return repository.findById(sku).map(JpaItemDao::toSnapshot);
    }

    @Override
    public List<ItemSnapshot> findBelowStock(int threshold) {
        return repository.findByStockLessThanOrderBySkuAsc(threshold).stream()
                .map(JpaItemDao::toSnapshot)
                .toList();
    }

    @Override
    public void decrementStockForUpdate(String sku, int quantity) {
        InventoryItem item = repository.findBySkuForUpdate(sku)
                .orElseThrow(() -> new ItemNotFoundException(sku));
        item.setStock(Math.max(0, item.getStock() - quantity));
        // Managed entity — flushed with the caller's transaction, which is also
        // what holds the row lock until commit.
    }

    @Override
    public long count() {
        return repository.count();
    }

    @Override
    public void saveAll(List<ItemSnapshot> items) {
        List<InventoryItem> entities = new ArrayList<>(items.size());
        for (ItemSnapshot item : items) {
            entities.add(new InventoryItem(item.sku(), item.name(), item.price(), item.stock()));
        }
        repository.saveAll(entities);
    }

    private static ItemSnapshot toSnapshot(InventoryItem item) {
        return new ItemSnapshot(item.getSku(), item.getName(), item.getPrice(), item.getStock());
    }
}
