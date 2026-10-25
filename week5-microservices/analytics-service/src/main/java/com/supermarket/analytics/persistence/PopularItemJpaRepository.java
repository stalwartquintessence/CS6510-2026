package com.supermarket.analytics.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Package-private: reachable only through {@link JpaPopularItemsDao}. */
interface PopularItemJpaRepository extends JpaRepository<PopularItemEntity, String> {

    /** The current ranking, best-ranked first. */
    List<PopularItemEntity> findAllByOrderByRankAsc();
}
