package com.supermarket.inventory.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Package-private: reachable only through {@link JpaStockDao}. */
interface StockItemJpaRepository extends JpaRepository<StockItemEntity, String> {

    /**
     * The row under a PESSIMISTIC_WRITE (SELECT … FOR UPDATE) lock held until the
     * surrounding transaction ends: the same primitive weeks 1–4 used, now inside the
     * one service that owns stock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockItemEntity s where s.sku = :sku")
    Optional<StockItemEntity> findBySkuForUpdate(@Param("sku") String sku);

    /** Items whose stock has dropped strictly below the given threshold. */
    List<StockItemEntity> findByStockLessThanOrderBySkuAsc(int threshold);
}
