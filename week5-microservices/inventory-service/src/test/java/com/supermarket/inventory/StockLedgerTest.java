package com.supermarket.inventory;

import com.supermarket.inventory.model.DeductOutcome;
import com.supermarket.inventory.model.RequestedLine;
import com.supermarket.inventory.model.SaleLine;
import com.supermarket.inventory.model.SaleRecord;
import com.supermarket.inventory.model.SaleStatus;
import com.supermarket.inventory.model.StockItem;
import com.supermarket.inventory.persistence.SaleDao;
import com.supermarket.inventory.persistence.StockDao;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inventory half of the checkout saga against in-memory DAOs. What makes the saga
 * safe to retry and to compensate lives here: idempotency by sale id, the tombstone, and
 * restoring what was taken rather than what was asked for. (The row locks themselves are
 * PostgreSQL's; the load test checks them.)
 */
class StockLedgerTest {

    private final FakeStock stock = new FakeStock(Map.of("SKU-A", 10, "SKU-B", 5, "SKU-C", 2));
    private final FakeSales sales = new FakeSales();
    private final StockLedger ledger = new StockLedger(stock, sales, LockOrder.SKU_ASCENDING);

    @Test
    void deductTakesTheStockOnceHoweverOftenItIsRepeated() {
        List<RequestedLine> lines = List.of(new RequestedLine("SKU-A", 3), new RequestedLine("SKU-B", 2));

        DeductOutcome first = ledger.deduct("tx-1/1", lines);
        DeductOutcome again = ledger.deduct("tx-1/1", lines);

        assertTrue(first.applied());
        assertEquals(List.of(new SaleLine("SKU-A", 3, 3), new SaleLine("SKU-B", 2, 2)), first.lines());
        assertEquals(first, again, "a repeat returns the stored result");
        assertEquals(7, stock.of("SKU-A"));
        assertEquals(3, stock.of("SKU-B"));
    }

    @Test
    void stockIsFlooredAtZeroAndRestoreGivesBackOnlyWhatWasTaken() {
        DeductOutcome outcome = ledger.deduct("tx-1/1", List.of(new RequestedLine("SKU-C", 5)));

        assertEquals(List.of(new SaleLine("SKU-C", 5, 2)), outcome.lines());
        assertEquals(0, stock.of("SKU-C"));

        assertEquals(2, ledger.restore("tx-1/1"));
        assertEquals(2, stock.of("SKU-C"), "restoring the 5 requested would have created 3 units");
    }

    @Test
    void restoringTwicePutsStockBackOnce() {
        ledger.deduct("tx-1/1", List.of(new RequestedLine("SKU-A", 4)));

        assertEquals(4, ledger.restore("tx-1/1"));
        assertEquals(0, ledger.restore("tx-1/1"));
        assertEquals(10, stock.of("SKU-A"));
        assertEquals(SaleStatus.REVERTED, sales.status.get("tx-1/1"));
    }

    @Test
    void aRestoreThatOvertakesItsDeductLeavesATombstoneThatRefusesIt() {
        assertEquals(0, ledger.restore("tx-1/1"));

        DeductOutcome late = ledger.deduct("tx-1/1", List.of(new RequestedLine("SKU-A", 4)));

        assertFalse(late.applied());
        assertEquals(10, stock.of("SKU-A"));
        assertTrue(ledger.deduct("tx-1/2", List.of(new RequestedLine("SKU-A", 4))).applied(),
                "the basket's next attempt has its own sale id and is not blocked");
        assertEquals(6, stock.of("SKU-A"));
    }

    @Test
    void rowsAreLockedInSkuOrderWhateverOrderTheLinesArrive() {
        ledger.deduct("tx-1/1", List.of(
                new RequestedLine("SKU-C", 1), new RequestedLine("SKU-A", 1), new RequestedLine("SKU-B", 1)));

        assertEquals(List.of("SKU-A", "SKU-B", "SKU-C"), stock.lockOrder);
    }

    @Test
    void theDescendingExperimentLocksInReverseForDeductAndRestoreAlike() {
        StockLedger descending = new StockLedger(stock, sales, LockOrder.SKU_DESCENDING);
        descending.deduct("tx-1/1", List.of(
                new RequestedLine("SKU-A", 1), new RequestedLine("SKU-C", 1), new RequestedLine("SKU-B", 1)));
        descending.restore("tx-1/1");

        assertEquals(List.of("SKU-C", "SKU-B", "SKU-A", "SKU-C", "SKU-B", "SKU-A"), stock.lockOrder,
                "a restore must lock in the same order as the deduct, or the two could deadlock");
        assertEquals(10, stock.of("SKU-A"));
    }

    private static final class FakeStock implements StockDao {

        final Map<String, Integer> levels;
        final List<String> lockOrder = new ArrayList<>();

        FakeStock(Map<String, Integer> initial) {
            this.levels = new HashMap<>(initial);
        }

        int of(String sku) {
            return levels.get(sku);
        }

        @Override
        public int decrementForUpdate(String sku, int quantity) {
            lockOrder.add(sku);
            int taken = Math.min(quantity, levels.get(sku));
            levels.put(sku, levels.get(sku) - taken);
            return taken;
        }

        @Override
        public void incrementForUpdate(String sku, int quantity) {
            lockOrder.add(sku);
            levels.put(sku, levels.get(sku) + quantity);
        }

        @Override public List<StockItem> findBelowStock(int threshold) { throw new UnsupportedOperationException(); }
        @Override public long count() { return levels.size(); }
        @Override public void saveAll(List<StockItem> items) { throw new UnsupportedOperationException(); }
    }

    private static final class FakeSales implements SaleDao {

        final Map<String, SaleStatus> status = new HashMap<>();
        final Map<String, List<SaleLine>> lines = new HashMap<>();

        @Override
        public boolean insertIfAbsent(String saleId, SaleStatus initial) {
            return status.putIfAbsent(saleId, initial) == null;
        }

        @Override
        public Optional<SaleRecord> findForUpdate(String saleId) {
            return Optional.ofNullable(status.get(saleId))
                    .map(s -> new SaleRecord(saleId, s, lines.getOrDefault(saleId, List.of())));
        }

        @Override
        public void saveLines(String saleId, List<SaleLine> saleLines) {
            lines.put(saleId, List.copyOf(saleLines));
        }

        @Override
        public void markReverted(String saleId) {
            status.put(saleId, SaleStatus.REVERTED);
        }
    }
}
