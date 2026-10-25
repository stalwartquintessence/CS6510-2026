package com.supermarket.catalog.persistence;

import com.supermarket.catalog.model.ItemView;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
class JpaItemDao implements ItemDao {

    private final ItemJpaRepository repository;

    JpaItemDao(ItemJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<ItemView> findAllOrderBySku() {
        return repository.findAll(Sort.by("sku")).stream()
                .map(JpaItemDao::toView)
                .toList();
    }

    @Override
    public Optional<ItemView> findBySku(String sku) {
        return repository.findById(sku).map(JpaItemDao::toView);
    }

    @Override
    public long count() {
        return repository.count();
    }

    @Override
    public void saveAll(List<ItemView> items) {
        List<ItemEntity> entities = new ArrayList<>(items.size());
        for (ItemView item : items) {
            entities.add(new ItemEntity(item.sku(), item.name(), item.price()));
        }
        repository.saveAll(entities);
    }

    private static ItemView toView(ItemEntity item) {
        return new ItemView(item.getSku(), item.getName(), item.getPrice());
    }
}
