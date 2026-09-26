package com.supermarket.persistence;

import com.supermarket.domain.Basket;

import java.util.Optional;

/**
 * Loads and stores the checkout aggregate, mapping between {@link Basket} and
 * the JPA entities. The transactions layer never sees an entity.
 */
public interface TransactionDao {

    /** Persist a new OPEN basket for the given station. */
    Basket create(String transactionId, String stationId);

    Optional<Basket> findById(String transactionId);

    /**
     * Write the aggregate's current state back. Must be called inside the same
     * transaction the basket was loaded in, so the underlying entity is still
     * managed and this is a dirty-check rather than a merge.
     */
    void save(Basket basket);
}
