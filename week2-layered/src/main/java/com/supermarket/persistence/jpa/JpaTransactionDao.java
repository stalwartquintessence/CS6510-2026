package com.supermarket.persistence.jpa;

import com.supermarket.domain.Basket;
import com.supermarket.domain.BasketLine;
import com.supermarket.domain.BasketStatus;
import com.supermarket.domain.error.TransactionNotFoundException;
import com.supermarket.persistence.TransactionDao;
import com.supermarket.persistence.entity.Transaction;
import com.supermarket.persistence.entity.TransactionItem;
import com.supermarket.persistence.entity.TransactionStatus;
import org.springframework.stereotype.Repository;

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
        Transaction tx = new Transaction(transactionId, stationId);
        repository.save(tx);
        return toBasket(tx);
    }

    @Override
    public Optional<Basket> findById(String transactionId) {
        return repository.findById(transactionId).map(JpaTransactionDao::toBasket);
    }

    @Override
    public void save(Basket basket) {
        Transaction tx = repository.findById(basket.transactionId())
                .orElseThrow(() -> new TransactionNotFoundException(basket.transactionId()));

        tx.setTotal(basket.total());
        tx.setStatus(TransactionStatus.valueOf(basket.status().name()));
        tx.setCompletedAt(basket.completedAt());

        // Reconcile lines onto the managed collection: update quantities in
        // place, append anything new. Lines are never removed from a basket.
        List<TransactionItem> managed = tx.getLines();
        for (BasketLine line : basket.lines()) {
            TransactionItem existing = null;
            for (TransactionItem candidate : managed) {
                if (candidate.getSku().equals(line.sku())) {
                    existing = candidate;
                    break;
                }
            }
            if (existing == null) {
                managed.add(new TransactionItem(
                        line.sku(), line.name(), line.unitPrice(), line.quantity()));
            } else if (existing.getQuantity() != line.quantity()) {
                existing.setQuantity(line.quantity());
            }
        }
    }

    private static Basket toBasket(Transaction tx) {
        List<BasketLine> lines = new ArrayList<>(tx.getLines().size());
        for (TransactionItem line : tx.getLines()) {
            lines.add(new BasketLine(
                    line.getSku(), line.getName(), line.getUnitPrice(), line.getQuantity()));
        }
        return new Basket(
                tx.getId(),
                tx.getStationId(),
                BasketStatus.valueOf(tx.getStatus().name()),
                tx.getCreatedAt(),
                tx.getCompletedAt(),
                lines);
    }
}
