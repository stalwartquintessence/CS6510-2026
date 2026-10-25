package com.supermarket.inventory.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Package-private: reachable only through {@link JpaSaleDao}. */
interface SaleLineJpaRepository extends JpaRepository<SaleLineEntity, Long> {

    List<SaleLineEntity> findBySaleIdOrderBySkuAsc(String saleId);
}
