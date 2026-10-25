package com.supermarket.transaction.persistence;

import com.supermarket.transaction.domain.Basket;
import com.supermarket.transaction.domain.BasketStatus;
import com.supermarket.transaction.domain.SagaRef;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Loads and stores the checkout aggregate, mapping between {@link Basket} and the JPA
 * entities, and moves the checkout saga between states. Private to the transaction
 * service, as is its database.
 */
public interface TransactionDao {

    /** Persist a new OPEN basket for the given station. */
    Basket create(String transactionId, String stationId);

    Optional<Basket> findById(String transactionId);

    /** As {@link #findById}, with the row locked until the caller's transaction ends. */
    Optional<Basket> findByIdForUpdate(String transactionId);

    /**
     * Write the aggregate's current state back. Must be called inside the same
     * transaction the basket was loaded in, so the underlying entity is still
     * managed and this is a dirty-check rather than a merge.
     */
    void save(Basket basket);

    /**
     * Move a saga from {@code from} to {@code to}, but only if it is still at
     * {@code from} in this attempt.
     *
     * @return true if this call made the change
     */
    boolean transition(String transactionId, int attempt, BasketStatus from, BasketStatus to);

    /** COMPLETING → COMPLETED for this attempt, stamping the completion time. */
    boolean markCompleted(String transactionId, int attempt, Instant completedAt);

    /** Sagas in COMPLETING or COMPENSATING whose status last changed before {@code before}, oldest first. */
    List<SagaRef> findInFlight(Instant before, int limit);
}
