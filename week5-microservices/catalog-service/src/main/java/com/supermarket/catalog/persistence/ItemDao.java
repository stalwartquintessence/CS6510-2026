package com.supermarket.catalog.persistence;

import com.supermarket.catalog.model.ItemView;

import java.util.List;
import java.util.Optional;

/**
 * The only door to the catalog database's {@code items} table.
 *
 * <p>Week 4's {@code ItemDao} lived in the shared library and was linked into every
 * service, so table ownership had to be policed by ArchUnit. This one is private to
 * the catalog service and so is its database: another service that wants an item
 * asks over gRPC.
 */
public interface ItemDao {

    /** Whole catalog, SKU order. */
    List<ItemView> findAllOrderBySku();

    Optional<ItemView> findBySku(String sku);

    /** How many items exist (drives idempotent seeding). */
    long count();

    /** Bulk insert, used only by seeding. */
    void saveAll(List<ItemView> items);
}
