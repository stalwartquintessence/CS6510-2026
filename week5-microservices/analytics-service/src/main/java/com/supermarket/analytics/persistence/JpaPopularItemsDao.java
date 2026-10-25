package com.supermarket.analytics.persistence;

import com.supermarket.analytics.PopularItemRow;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
class JpaPopularItemsDao implements PopularItemsDao {

    private final PopularItemJpaRepository repository;

    JpaPopularItemsDao(PopularItemJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<PopularItemRow> findRanking() {
        return repository.findAllByOrderByRankAsc().stream()
                .map(p -> new PopularItemRow(p.getSku(), p.getName(), p.getScanCount(), p.getRank()))
                .toList();
    }

    @Override
    public void replaceRanking(List<PopularItemRow> rows) {
        repository.deleteAllInBatch();
        List<PopularItemEntity> entities = new ArrayList<>(rows.size());
        for (PopularItemRow row : rows) {
            entities.add(new PopularItemEntity(row.sku(), row.name(), row.scanCount(), row.rank()));
        }
        repository.saveAll(entities);
    }
}
