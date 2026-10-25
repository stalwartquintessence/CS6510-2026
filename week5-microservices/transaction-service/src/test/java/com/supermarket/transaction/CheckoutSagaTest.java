package com.supermarket.transaction;

import com.supermarket.support.error.EmptyBasketException;
import com.supermarket.support.error.ServiceUnavailableException;
import com.supermarket.support.error.TransactionNotOpenException;
import com.supermarket.transaction.domain.Basket;
import com.supermarket.transaction.domain.BasketLine;
import com.supermarket.transaction.domain.BasketStatus;
import com.supermarket.transaction.domain.ItemSnapshot;
import com.supermarket.transaction.domain.SagaRef;
import com.supermarket.transaction.domain.ScanEvent;
import com.supermarket.transaction.model.ReceiptView;
import com.supermarket.transaction.persistence.ScanEventDao;
import com.supermarket.transaction.persistence.TransactionDao;
import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The checkout saga against an in-memory transaction database and an in-memory
 * inventory that can be told to be unreachable, to lose its reply after committing, or
 * to stall mid-call. No Spring context, no database, no network: the orchestration
 * logic and its failure paths, one at a time.
 *
 * <p>The property every test checks in the end is the one the load test checks across
 * the two real databases: a sale is either COMPLETED here with its stock taken there,
 * or OPEN here with nothing taken there.
 */
class CheckoutSagaTest {

    private static final Duration STUCK_AFTER = Duration.ofSeconds(30);
    private static final ItemSnapshot APPLE = new ItemSnapshot("SKU-A", "Apple", 0.50);
    private static final ItemSnapshot BREAD = new ItemSnapshot("SKU-B", "Bread", 2.25);

    private FakeTransactionDao transactions;
    private FakeInventory inventory;
    private BasketStore baskets;
    private CheckoutSaga saga;

    @BeforeEach
    void setUp() {
        transactions = new FakeTransactionDao();
        inventory = new FakeInventory();
        baskets = new BasketStore(transactions, new NoopScanEvents());
        saga = new CheckoutSaga(baskets, inventory, new SagaProperties(null, null, STUCK_AFTER, null));
    }

    @Test
    void completesTheBasketAndTakesTheStockOnce() {
        String tx = basketWith(APPLE, APPLE, BREAD);

        ReceiptView receipt = saga.complete(tx);

        assertEquals(3, receipt.itemCount());
        assertEquals(3.25, receipt.totalAmount());
        assertEquals(List.of("SKU-A", "SKU-B"), receipt.lines().stream().map(l -> l.sku()).toList());
        assertEquals(BasketStatus.COMPLETED, status(tx));
        assertEquals(1, inventory.deductCalls);
        assertEquals("APPLIED", inventory.sales.get(tx + "/1"));
        assertEquals(3, inventory.unitsTaken());
        assertThrows(TransactionNotOpenException.class, () -> saga.complete(tx), "a completed basket cannot complete again");
    }

    @Test
    void anUnreachableInventoryReopensTheBasketAndTheNextAttemptSucceeds() {
        String tx = basketWith(APPLE, BREAD);
        inventory.deductMode = Mode.UNREACHABLE;

        ServiceUnavailableException failure = assertThrows(ServiceUnavailableException.class, () -> saga.complete(tx));

        assertEquals("INVENTORY_UNAVAILABLE", failure.errorCode());
        assertEquals(BasketStatus.OPEN, status(tx));
        assertEquals("REVERTED", inventory.sales.get(tx + "/1"), "the compensation leaves a tombstone for attempt 1");
        assertEquals(0, inventory.unitsTaken());

        inventory.deductMode = Mode.OK;
        saga.complete(tx);

        assertEquals(BasketStatus.COMPLETED, status(tx));
        assertEquals("APPLIED", inventory.sales.get(tx + "/2"), "a retry is a new attempt with its own sale id");
        assertEquals(2, inventory.unitsTaken());
    }

    @Test
    void aReplyLostAfterTheDeductCommittedIsCompensated() {
        String tx = basketWith(APPLE, APPLE);
        inventory.deductMode = Mode.LOST_REPLY;   // inventory commits, then the call times out

        assertThrows(ServiceUnavailableException.class, () -> saga.complete(tx));

        assertEquals(BasketStatus.OPEN, status(tx));
        assertEquals("REVERTED", inventory.sales.get(tx + "/1"));
        assertEquals(0, inventory.unitsTaken(), "the stock taken by the lost call was put back");
    }

    @Test
    void aFailedCompensationIsLeftForRecoveryWhichFinishesItLater() {
        String tx = basketWith(BREAD);
        inventory.deductMode = Mode.LOST_REPLY;
        inventory.restoreFailures = 1;

        assertThrows(ServiceUnavailableException.class, () -> saga.complete(tx));
        assertEquals(BasketStatus.COMPENSATING, status(tx));
        assertEquals(1, inventory.unitsTaken(), "still taken: the compensation did not get through");

        assertEquals(0, saga.recoverStuck(Instant.now()), "too young to be recovered yet");
        assertEquals(1, saga.recoverStuck(Instant.now().plus(STUCK_AFTER).plusSeconds(1)));

        assertEquals(BasketStatus.OPEN, status(tx));
        assertEquals(0, inventory.unitsTaken());
        assertEquals(2, inventory.restoreCalls);
    }

    @Test
    void aSagaAbandonedByACrashIsCompensatedOnceItIsStuck() {
        String tx = basketWith(APPLE, BREAD);
        // The process died between the stock step and marking the basket COMPLETED:
        Basket frozen = baskets.freeze(tx);
        inventory.deduct(SagaRef.saleId(tx, frozen.sagaAttempt()), frozen.linesSortedBySku());
        assertEquals(BasketStatus.COMPLETING, status(tx));

        assertEquals(0, saga.recoverStuck(Instant.now()), "a saga that may still be running is left alone");
        assertEquals(BasketStatus.COMPLETING, status(tx));

        assertEquals(1, saga.recoverStuck(Instant.now().plus(STUCK_AFTER).plusSeconds(1)));
        assertEquals(BasketStatus.OPEN, status(tx));
        assertEquals(0, inventory.unitsTaken());
    }

    @Test
    void aSecondCompleteWhileTheFirstIsInFlightIsRefusedAndInventoryIsCalledOnce() throws Exception {
        String tx = basketWith(APPLE);
        inventory.holdDeduct = new CountDownLatch(1);

        CompletableFuture<ReceiptView> first = CompletableFuture.supplyAsync(() -> saga.complete(tx));
        await(() -> inventory.deductCalls == 1, "the first completion to reach inventory");

        assertThrows(TransactionNotOpenException.class, () -> saga.complete(tx));
        assertThrows(TransactionNotOpenException.class, () -> baskets.addUnit(tx, BREAD), "a frozen basket takes no scans");

        inventory.holdDeduct.countDown();
        assertEquals(1, first.get(5, TimeUnit.SECONDS).itemCount());
        assertEquals(1, inventory.deductCalls);
        assertEquals(BasketStatus.COMPLETED, status(tx));
    }

    @Test
    void whenRecoveryOvertakesASlowRequestTheLateDeductIsRefused() throws Exception {
        String tx = basketWith(APPLE, APPLE);
        inventory.holdDeduct = new CountDownLatch(1);

        CompletableFuture<ReceiptView> slow = CompletableFuture.supplyAsync(() -> saga.complete(tx));
        await(() -> inventory.deductCalls == 1, "the request to reach inventory");

        // The request has been stuck for longer than stuckAfter; recovery claims and compensates it
        // before its DeductStock has even been applied, which leaves a tombstone.
        assertEquals(1, saga.recoverStuck(Instant.now().plus(STUCK_AFTER).plusSeconds(1)));
        assertEquals(BasketStatus.OPEN, status(tx));

        inventory.holdDeduct.countDown();
        Exception failure = assertThrows(Exception.class, () -> slow.get(5, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof ServiceUnavailableException, failure.toString());
        assertEquals(0, inventory.unitsTaken(), "the late deduct hit the tombstone and took nothing");
        assertEquals(BasketStatus.OPEN, status(tx));
    }

    @Test
    void whenRecoveryClaimsASagaAfterItsDeductTheRequestCannotCompleteIt() throws Exception {
        String tx = basketWith(BREAD, BREAD, BREAD);
        inventory.holdAfterApply = new CountDownLatch(1);

        CompletableFuture<ReceiptView> slow = CompletableFuture.supplyAsync(() -> saga.complete(tx));
        await(() -> inventory.unitsTaken() == 3, "inventory to apply the deduct");

        // The stock is taken but the reply has not reached the orchestrator yet. Recovery,
        // believing the saga stuck, claims it and puts the stock back.
        assertEquals(1, saga.recoverStuck(Instant.now().plus(STUCK_AFTER).plusSeconds(1)));
        assertEquals(0, inventory.unitsTaken());

        inventory.holdAfterApply.countDown();
        Exception failure = assertThrows(Exception.class, () -> slow.get(5, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof ServiceUnavailableException, failure.toString());
        assertEquals("CHECKOUT_ABORTED", ((ServiceUnavailableException) failure.getCause()).errorCode());
        assertEquals(BasketStatus.OPEN, status(tx), "step 3 lost the race and must not mark it COMPLETED");
        assertEquals(0, inventory.unitsTaken());
    }

    @Test
    void anEmptyBasketIsRefusedBeforeInventoryIsEverCalled() {
        String tx = baskets.open("station-1").transactionId();

        assertThrows(EmptyBasketException.class, () -> saga.complete(tx));

        assertEquals(0, inventory.deductCalls);
        assertEquals(BasketStatus.OPEN, status(tx));
    }

    private String basketWith(ItemSnapshot... items) {
        String tx = baskets.open("station-1").transactionId();
        for (ItemSnapshot item : items) {
            baskets.addUnit(tx, item);
        }
        return tx;
    }

    private BasketStatus status(String tx) {
        return baskets.find(tx).status();
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("Timed out waiting for " + what);
            }
            Thread.onSpinWait();
        }
    }

    enum Mode { OK, UNREACHABLE, LOST_REPLY }

    /** The inventory ledger's semantics (idempotent by sale id, tombstones), in memory. */
    private static final class FakeInventory implements StockClient {

        final Map<String, String> sales = new HashMap<>();
        final Map<String, Integer> taken = new HashMap<>();
        volatile int deductCalls;
        volatile int restoreCalls;
        volatile Mode deductMode = Mode.OK;
        volatile int restoreFailures;
        volatile CountDownLatch holdDeduct;
        volatile CountDownLatch holdAfterApply;

        @Override
        public boolean deduct(String saleId, List<BasketLine> lines) {
            synchronized (this) {
                deductCalls++;
            }
            if (holdDeduct != null) {
                try {
                    holdDeduct.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            synchronized (this) {
                if (deductMode == Mode.UNREACHABLE) {
                    throw Status.UNAVAILABLE.withDescription("inventory down").asRuntimeException();
                }
                if ("REVERTED".equals(sales.get(saleId))) {
                    return false;
                }
                if (!sales.containsKey(saleId)) {
                    sales.put(saleId, "APPLIED");
                    taken.put(saleId, lines.stream().mapToInt(BasketLine::quantity).sum());
                }
                if (deductMode == Mode.LOST_REPLY) {
                    throw Status.DEADLINE_EXCEEDED.withDescription("reply lost").asRuntimeException();
                }
            }
            if (holdAfterApply != null) {   // committed, but the reply is slow to arrive
                try {
                    holdAfterApply.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return true;
        }

        @Override
        public synchronized int restore(String saleId) {
            restoreCalls++;
            if (restoreFailures > 0) {
                restoreFailures--;
                throw Status.UNAVAILABLE.withDescription("inventory down").asRuntimeException();
            }
            String status = sales.put(saleId, "REVERTED");
            return "APPLIED".equals(status) ? taken.remove(saleId) : 0;
        }

        synchronized int unitsTaken() {
            return taken.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /** The transaction database, in memory, with the real DAO's conditional-update semantics. */
    private static final class FakeTransactionDao implements TransactionDao {

        private final Map<String, Row> rows = new HashMap<>();

        private static final class Row {
            String stationId;
            BasketStatus status = BasketStatus.OPEN;
            Instant startedAt = Instant.now();
            Instant completedAt;
            int attempt;
            Instant updatedAt;
            List<BasketLine> lines = new ArrayList<>();
        }

        @Override
        public synchronized Basket create(String transactionId, String stationId) {
            Row row = new Row();
            row.stationId = stationId;
            rows.put(transactionId, row);
            return toBasket(transactionId, row);
        }

        @Override
        public synchronized Optional<Basket> findById(String transactionId) {
            return Optional.ofNullable(rows.get(transactionId)).map(row -> toBasket(transactionId, row));
        }

        @Override
        public Optional<Basket> findByIdForUpdate(String transactionId) {
            return findById(transactionId);
        }

        @Override
        public synchronized void save(Basket basket) {
            Row row = rows.get(basket.transactionId());
            if (row.status != basket.status()) {
                row.updatedAt = Instant.now();
            }
            row.status = basket.status();
            row.completedAt = basket.completedAt();
            row.attempt = basket.sagaAttempt();
            row.lines = copy(basket.lines());
        }

        @Override
        public synchronized boolean transition(String transactionId, int attempt, BasketStatus from, BasketStatus to) {
            Row row = rows.get(transactionId);
            if (row.attempt != attempt || row.status != from) {
                return false;
            }
            row.status = to;
            row.updatedAt = Instant.now();
            return true;
        }

        @Override
        public synchronized boolean markCompleted(String transactionId, int attempt, Instant completedAt) {
            if (!transition(transactionId, attempt, BasketStatus.COMPLETING, BasketStatus.COMPLETED)) {
                return false;
            }
            rows.get(transactionId).completedAt = completedAt;
            return true;
        }

        @Override
        public synchronized List<SagaRef> findInFlight(Instant before, int limit) {
            List<SagaRef> found = new ArrayList<>();
            rows.forEach((id, row) -> {
                boolean inFlight = row.status == BasketStatus.COMPLETING || row.status == BasketStatus.COMPENSATING;
                if (inFlight && row.updatedAt.isBefore(before) && found.size() < limit) {
                    found.add(new SagaRef(id, row.attempt, row.status));
                }
            });
            return found;
        }

        private static Basket toBasket(String id, Row row) {
            return new Basket(id, row.stationId, row.status, row.startedAt, row.completedAt, row.attempt, copy(row.lines));
        }

        private static List<BasketLine> copy(List<BasketLine> lines) {
            List<BasketLine> copy = new ArrayList<>(lines.size());
            for (BasketLine line : lines) {
                copy.add(new BasketLine(line.sku(), line.name(), line.unitPrice(), line.quantity()));
            }
            return copy;
        }
    }

    private static final class NoopScanEvents implements ScanEventDao {
        @Override public void append(String sku, Instant scannedAt) { }
        @Override public List<ScanEvent> findAfter(long afterId, int limit) { return List.of(); }
        @Override public long maxId() { return 0; }
    }
}
