package com.supermarket.persistence.jpa;

import com.supermarket.domain.PopularItemRow;
import com.supermarket.persistence.PopularItemsDao;
import com.supermarket.persistence.entity.PopularItem;
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
        List<PopularItem> entities = new ArrayList<>(rows.size());
        for (PopularItemRow row : rows) {
            entities.add(new PopularItem(row.sku(), row.name(), row.scanCount(), row.rank()));
        }
        repository.saveAll(entities);
    }
}
