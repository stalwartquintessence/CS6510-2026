package com.supermarket.persistence.jpa;

import com.supermarket.persistence.entity.PopularItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Package-private: reachable only through {@link JpaPopularItemsDao}. */
interface PopularItemJpaRepository extends JpaRepository<PopularItem, String> {

    /** The current ranking, best-ranked first. */
    List<PopularItem> findAllByOrderByRankAsc();
}
