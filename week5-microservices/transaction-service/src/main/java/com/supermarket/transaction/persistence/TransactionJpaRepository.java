package com.supermarket.transaction.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Package-private: reachable only through {@link JpaTransactionDao}. */
interface TransactionJpaRepository extends JpaRepository<TransactionEntity, String> {

    /**
     * The transaction row under a {@code SELECT … FOR UPDATE} lock. Scans and the saga's
     * freeze step both take it, so a scan can never slip into a basket the saga has
     * already frozen. Uncontended in practice: one station drives one transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TransactionEntity t where t.id = :id")
    Optional<TransactionEntity> findByIdForUpdate(@Param("id") String id);

    /**
     * A conditional state change: applies only if the saga is still at {@code from} in
     * this attempt. Whoever's update matches owns the next step; everyone else sees 0
     * and backs off. This is how the request thread and the recovery job avoid both
     * finishing the same saga.
     */
    @Modifying
    @Query("update TransactionEntity t set t.status = :to, t.sagaUpdatedAt = :now "
            + "where t.id = :id and t.sagaAttempt = :attempt and t.status = :from")
    int transition(@Param("id") String id,
                   @Param("attempt") int attempt,
                   @Param("from") TransactionStatus from,
                   @Param("to") TransactionStatus to,
                   @Param("now") Instant now);

    @Modifying
    @Query("update TransactionEntity t set t.status = :completed, t.completedAt = :at, t.sagaUpdatedAt = :at "
            + "where t.id = :id and t.sagaAttempt = :attempt and t.status = :completing")
    int complete(@Param("id") String id,
                 @Param("attempt") int attempt,
                 @Param("completing") TransactionStatus completing,
                 @Param("completed") TransactionStatus completed,
                 @Param("at") Instant at);

    /**
     * Sagas still in flight whose last change is older than {@code before}. The statuses
     * are literals, not parameters, so PostgreSQL can use the partial index on in-flight
     * rows ({@code db/transaction.sql}) instead of scanning every transaction.
     */
    @Query("select t.id as id, t.sagaAttempt as sagaAttempt, t.status as status from TransactionEntity t "
            + "where t.status in (com.supermarket.transaction.persistence.TransactionStatus.COMPLETING, "
            + "com.supermarket.transaction.persistence.TransactionStatus.COMPENSATING) "
            + "and t.sagaUpdatedAt < :before order by t.sagaUpdatedAt")
    List<InFlightRow> findInFlight(@Param("before") Instant before, Limit limit);

    /** Closed projection for {@link #findInFlight}: the saga's identity, without its lines. */
    interface InFlightRow {
        String getId();

        int getSagaAttempt();

        TransactionStatus getStatus();
    }
}
