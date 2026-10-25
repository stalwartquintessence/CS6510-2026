package com.supermarket.transaction;

import com.supermarket.support.error.ServiceUnavailableException;
import com.supermarket.transaction.domain.Basket;
import com.supermarket.transaction.domain.BasketLine;
import com.supermarket.transaction.domain.BasketStatus;
import com.supermarket.transaction.domain.SagaRef;
import com.supermarket.transaction.model.ReceiptLineView;
import com.supermarket.transaction.model.ReceiptView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates {@code POST /transactions/{id}/complete} across the two databases
 * it now touches: the basket here, the stock in the inventory service.
 *
 * <pre>
 *   1. freeze            OPEN → COMPLETING                (this service's DB)
 *   2. DeductStock       take the stock, idempotent by sale id  (inventory's DB)
 *   3. complete          COMPLETING → COMPLETED, return the receipt
 *
 *   2 fails or times out:
 *      claim             COMPLETING → COMPENSATING
 *      RestoreStock      put back whatever step 2 took (idempotent; leaves a tombstone if it took nothing)
 *      reopen            COMPENSATING → OPEN, and the client gets 503
 * </pre>
 *
 * <p>Why compensate rather than retry forward: the contract is synchronous, so by the
 * time the stock step fails the client is being told the sale did not happen. Undoing
 * it makes both databases agree with what the client was told. A 200 is only ever sent
 * after step 3 commits, so a client that saw a receipt always had its stock decremented.
 *
 * <p>If this process dies mid-saga, or a compensation cannot reach inventory, the
 * basket is left COMPLETING or COMPENSATING. {@link #recoverStuck} (run by
 * {@link SagaRecovery}) finishes those by compensating. Every transition is a
 * conditional update on (status, attempt), so a request thread and the recovery job
 * can never both act on the same step.
 */
@Component
class CheckoutSaga {

    private static final Logger log = LoggerFactory.getLogger(CheckoutSaga.class);
    private static final int RECOVERY_BATCH = 100;

    private final BasketStore baskets;
    private final StockClient stock;
    private final Duration stuckAfter;

    CheckoutSaga(BasketStore baskets, StockClient stock, SagaProperties properties) {
        this.baskets = baskets;
        this.stock = stock;
        this.stuckAfter = properties.stuckAfter();
    }

    ReceiptView complete(String transactionId) {
        Basket basket = baskets.freeze(transactionId);
        int attempt = basket.sagaAttempt();
        String saleId = SagaRef.saleId(transactionId, attempt);
        List<BasketLine> lines = basket.linesSortedBySku();

        boolean deducted;
        try {
            deducted = stock.deduct(saleId, lines);
            if (!deducted) {
                log.warn("Inventory refused sale {}: already restored", saleId);
            }
        } catch (RuntimeException e) {
            log.warn("Stock step of sale {} failed: {}", saleId, e.toString());
            deducted = false;
        }

        if (!deducted) {
            if (baskets.move(transactionId, attempt, BasketStatus.COMPLETING, BasketStatus.COMPENSATING)) {
                compensate(new SagaRef(transactionId, attempt, BasketStatus.COMPENSATING));
            }
            throw new ServiceUnavailableException("INVENTORY_UNAVAILABLE",
                    "Stock could not be updated, so transaction " + transactionId + " was not completed");
        }

        Instant completedAt = Instant.now();
        if (!baskets.markCompleted(transactionId, attempt, completedAt)) {
            // Only the recovery job moves a COMPLETING saga on without us, and only once it
            // is stuckAfter old: it claimed this one first and is putting the stock back.
            throw new ServiceUnavailableException("CHECKOUT_ABORTED",
                    "Transaction " + transactionId + " took too long to complete and was rolled back");
        }
        return receipt(basket, lines, completedAt);
    }

    /**
     * Finish every saga that has been in flight for longer than {@code stuckAfter}, by
     * compensating it. Works through the backlog a batch at a time — an inventory outage
     * under load leaves one stuck saga per failed completion, i.e. hundreds — and stops
     * early if a compensation fails, since inventory is then evidently still unreachable.
     *
     * @return how many were finished; the rest stay for the next pass
     */
    int recoverStuck(Instant now) {
        Instant before = now.minus(stuckAfter);
        int recovered = 0;
        while (true) {
            List<SagaRef> batch = baskets.inFlight(before, RECOVERY_BATCH);
            int finished = 0;
            for (SagaRef saga : batch) {
                if (saga.status() == BasketStatus.COMPLETING
                        && !baskets.move(saga.transactionId(), saga.attempt(), BasketStatus.COMPLETING, BasketStatus.COMPENSATING)) {
                    continue;   // it moved on since we looked
                }
                if (compensate(saga)) {
                    finished++;
                }
            }
            recovered += finished;
            if (batch.size() < RECOVERY_BATCH || finished < batch.size()) {
                return recovered;
            }
        }
    }

    /**
     * Put the stock back and reopen the basket. The caller must already own the
     * COMPENSATING state.
     *
     * @return false if inventory could not be reached; the saga stays COMPENSATING and
     *         the recovery job tries again
     */
    private boolean compensate(SagaRef saga) {
        int restored;
        try {
            restored = stock.restore(saga.saleId());
        } catch (RuntimeException e) {
            log.warn("Compensation of sale {} failed, left for recovery: {}", saga.saleId(), e.toString());
            return false;
        }
        baskets.move(saga.transactionId(), saga.attempt(), BasketStatus.COMPENSATING, BasketStatus.OPEN);
        log.info("Sale {} compensated ({} units restored); transaction reopened", saga.saleId(), restored);
        return true;
    }

    private static ReceiptView receipt(Basket basket, List<BasketLine> lines, Instant completedAt) {
        List<ReceiptLineView> receiptLines = new ArrayList<>(lines.size());
        for (BasketLine line : lines) {
            receiptLines.add(new ReceiptLineView(line.sku(), line.name(), line.unitPrice(), line.quantity()));
        }
        return new ReceiptView(
                basket.transactionId(),
                basket.stationId(),
                basket.itemCount(),
                basket.total(),
                basket.startedAt(),
                completedAt,
                receiptLines);
    }
}
