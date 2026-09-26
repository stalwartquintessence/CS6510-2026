package com.supermarket.persistence;

import com.supermarket.domain.ItemSnapshot;

import java.util.List;
import java.util.Optional;

/**
 * The only door to the {@code items} table.
 *
 * <p>Week 1 let four different collaborators inject {@code InventoryRepository}
 * directly, so nothing owned the table. Here the Spring Data interface is
 * package-private to {@code persistence.jpa} and every caller goes through this
 * port instead.
 */
public interface ItemDao {

    /** Whole catalog, SKU order. */
    List<ItemSnapshot> findAllOrderBySku();

    /** One item, for scan-time price/name resolution. */
    Optional<ItemSnapshot> findBySku(String sku);

    /** Items whose stock has dropped strictly below the given threshold. */
    List<ItemSnapshot> findBelowStock(int threshold);

    /**
     * Take a {@code PESSIMISTIC_WRITE} (SELECT … FOR UPDATE) lock on the row and
     * decrement it, clamped at zero.
     *
     * <p>This is the primitive the whole correctness invariant rests on. It must
     * run inside the caller's transaction so the lock is held until commit —
     * callers enforce that with {@code @Transactional}.
     *
     * @throws com.supermarket.domain.error.ItemNotFoundException if the SKU is unknown
     */
    void decrementStockForUpdate(String sku, int quantity);

    /** How many catalog rows exist (drives idempotent seeding). */
    long count();

    /** Bulk insert, used only by catalog seeding. */
    void saveAll(List<ItemSnapshot> items);
}
