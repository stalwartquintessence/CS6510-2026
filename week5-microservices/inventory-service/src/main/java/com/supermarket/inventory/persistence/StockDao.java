package com.supermarket.inventory.persistence;

import com.supermarket.inventory.model.StockItem;

import java.util.List;

/**
 * The only door to the inventory database's {@code stock_items} table. Private to the
 * inventory service, as is the database: no other service can read stock except
 * through its RPCs, so the "check, then decrement" can never be split across a network
 * call — both halves happen in {@link #decrementForUpdate}.
 */
public interface StockDao {

    /** Items whose stock has dropped strictly below the given threshold, SKU order. */
    List<StockItem> findBelowStock(int threshold);

    /**
     * Take a {@code PESSIMISTIC_WRITE} lock on the row and decrement it, floored at zero.
     * Must run inside the caller's transaction, so the lock is held until commit.
     *
     * @return the units actually taken: {@code min(quantity, stock)}
     * @throws com.supermarket.support.error.ItemNotFoundException if the SKU is unknown
     */
    int decrementForUpdate(String sku, int quantity);

    /** The compensation's counterpart: lock the row and add {@code quantity} back. */
    void incrementForUpdate(String sku, int quantity);

    /** How many stock rows exist (drives idempotent seeding). */
    long count();

    /** Bulk insert, used only by seeding. */
    void saveAll(List<StockItem> items);
}
