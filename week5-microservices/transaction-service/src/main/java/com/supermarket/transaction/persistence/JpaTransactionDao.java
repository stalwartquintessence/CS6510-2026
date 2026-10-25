package com.supermarket.transaction.persistence;

import com.supermarket.support.error.TransactionNotFoundException;
import com.supermarket.transaction.domain.Basket;
import com.supermarket.transaction.domain.BasketLine;
import com.supermarket.transaction.domain.BasketStatus;
import com.supermarket.transaction.domain.SagaRef;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Maps the {@link Basket} aggregate to and from the JPA entities.
 *
 * <p>{@link #save} deliberately re-reads the entity rather than merging a
 * detached copy. Within the caller's transaction that read is a first-level
 * cache hit — no extra SQL — so the write still happens by Hibernate dirty
 * checking at commit, exactly as in week 1. Keeping the SQL identical is what
 * makes the week-over-week latency comparison meaningful.
 */
@Repository
class JpaTransactionDao implements TransactionDao {

    private final TransactionJpaRepository repository;

    JpaTransactionDao(TransactionJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Basket create(String transactionId, String stationId) {
        TransactionEntity tx = new TransactionEntity(transactionId, stationId);
        repository.save(tx);
        return toBasket(tx);
    }

    @Override
    public Optional<Basket> findById(String transactionId) {
        return repository.findById(transactionId).map(JpaTransactionDao::toBasket);
    }

    @Override
    public Optional<Basket> findByIdForUpdate(String transactionId) {
        return repository.findByIdForUpdate(transactionId).map(JpaTransactionDao::toBasket);
    }

    @Override
    public void save(Basket basket) {
        TransactionEntity tx = repository.findById(basket.transactionId())
                .orElseThrow(() -> new TransactionNotFoundException(basket.transactionId()));

        TransactionStatus status = TransactionStatus.valueOf(basket.status().name());
        if (tx.getStatus() != status) {
            tx.setSagaUpdatedAt(Instant.now());
        }
        tx.setTotal(basket.total());
        tx.setStatus(status);
        tx.setCompletedAt(basket.completedAt());
        tx.setSagaAttempt(basket.sagaAttempt());

        // Reconcile lines onto the managed collection: update quantities in
        // place, append anything new. Lines are never removed from a basket.
        List<TransactionItemEntity> managed = tx.getLines();
        for (BasketLine line : basket.lines()) {
            TransactionItemEntity existing = null;
            for (TransactionItemEntity candidate : managed) {
                if (candidate.getSku().equals(line.sku())) {
                    existing = candidate;
                    break;
                }
            }
            if (existing == null) {
                managed.add(new TransactionItemEntity(
                        line.sku(), line.name(), line.unitPrice(), line.quantity()));
            } else if (existing.getQuantity() != line.quantity()) {
                existing.setQuantity(line.quantity());
            }
        }
    }

    @Override
    public boolean transition(String transactionId, int attempt, BasketStatus from, BasketStatus to) {
        return repository.transition(transactionId, attempt, stored(from), stored(to), Instant.now()) == 1;
    }

    @Override
    public boolean markCompleted(String transactionId, int attempt, Instant completedAt) {
        return repository.complete(transactionId, attempt,
                TransactionStatus.COMPLETING, TransactionStatus.COMPLETED, completedAt) == 1;
    }

    @Override
    public List<SagaRef> findInFlight(Instant before, int limit) {
        return repository.findInFlight(before, Limit.of(limit)).stream()
                .map(row -> new SagaRef(row.getId(), row.getSagaAttempt(), BasketStatus.valueOf(row.getStatus().name())))
                .toList();
    }

    private static TransactionStatus stored(BasketStatus status) {
        return TransactionStatus.valueOf(status.name());
    }

    private static Basket toBasket(TransactionEntity tx) {
        List<BasketLine> lines = new ArrayList<>(tx.getLines().size());
        for (TransactionItemEntity line : tx.getLines()) {
            lines.add(new BasketLine(
                    line.getSku(), line.getName(), line.getUnitPrice(), line.getQuantity()));
        }
        return new Basket(
                tx.getId(),
                tx.getStationId(),
                BasketStatus.valueOf(tx.getStatus().name()),
                tx.getCreatedAt(),
                tx.getCompletedAt(),
                tx.getSagaAttempt(),
                lines);
    }
}
