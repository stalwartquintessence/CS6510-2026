package com.supermarket.catalog.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Package-private: reachable only through {@link JpaItemDao}. */
interface ItemJpaRepository extends JpaRepository<ItemEntity, String> {
}
