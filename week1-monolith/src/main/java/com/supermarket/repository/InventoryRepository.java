package com.supermarket.repository;

import com.supermarket.model.InventoryItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<InventoryItem, String> {

    /**
     * Fetch a single inventory row while holding a PESSIMISTIC_WRITE (SELECT ...
     * FOR UPDATE) lock on it for the duration of the surrounding transaction.
     * This is the primitive that makes the completion decrement atomic and
     * prevents the "two stations both buy the last unit" lost-update bug.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InventoryItem i where i.sku = :sku")
    Optional<InventoryItem> findBySkuForUpdate(@Param("sku") String sku);

    /** Items whose stock has dropped strictly below the given threshold. */
    List<InventoryItem> findByStockLessThanOrderBySkuAsc(int threshold);
}
