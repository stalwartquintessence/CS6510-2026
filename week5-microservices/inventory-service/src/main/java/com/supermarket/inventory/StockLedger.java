package com.supermarket.inventory;

import com.supermarket.inventory.model.DeductOutcome;
import com.supermarket.inventory.model.RequestedLine;
import com.supermarket.inventory.model.SaleLine;
import com.supermarket.inventory.model.SaleRecord;
import com.supermarket.inventory.model.SaleStatus;
import com.supermarket.inventory.persistence.SaleDao;
import com.supermarket.inventory.persistence.StockDao;
import com.supermarket.support.error.InvalidRequestException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The inventory service's half of the checkout saga: the stock step and its
 * compensation, each one local ACID transaction in the inventory database.
 *
 * <p>Weeks 1–4 decremented stock in the same database transaction that marked the
 * basket COMPLETED. The basket is now in another service's database, so the
 * transaction service orchestrates: it freezes the basket, calls {@link #deduct},
 * and either completes the basket or calls {@link #restore}. Both steps here are
 * idempotent by sale id, because the orchestrator (or its recovery job) may repeat
 * them after a timeout without knowing whether the first attempt committed.
 *
 * <p>The check and the decrement stay one atomic step, inside this service, under the
 * same SKU-ordered {@code SELECT … FOR UPDATE} as weeks 1–4: nothing outside reads
 * stock and then writes it back, so two stations still cannot both take the last unit.
 * Lock order is always the sale row first, then stock rows by SKU, in both methods,
 * so a deduct and a restore can never deadlock each other.
 */
@Component
public class StockLedger {

    private final StockDao stockDao;
    private final SaleDao saleDao;

    public StockLedger(StockDao stockDao, SaleDao saleDao) {
        this.stockDao = stockDao;
        this.saleDao = saleDao;
    }

    /**
     * Take the stock for one sale. Repeating a known sale id returns what was taken the
     * first time and takes nothing more; a sale id that was already restored (the
     * tombstone left by a restore that overtook its deduct) is refused.
     */
    @Transactional
    public DeductOutcome deduct(String saleId, List<RequestedLine> lines) {
        if (saleId == null || saleId.isBlank()) {
            throw new InvalidRequestException("saleId is required");
        }
        if (lines.isEmpty()) {
            throw new InvalidRequestException("a sale needs at least one line");
        }
        if (!saleDao.insertIfAbsent(saleId, SaleStatus.APPLIED)) {
            SaleRecord known = saleDao.findForUpdate(saleId).orElseThrow();
            return known.status() == SaleStatus.REVERTED
                    ? DeductOutcome.refused(saleId)
                    : DeductOutcome.applied(saleId, known.lines());
        }

        // Lock and decrement in a deterministic SKU order so two concurrent sales
        // touching the same pair of SKUs cannot deadlock.
        List<RequestedLine> ordered = new ArrayList<>(lines);
        ordered.sort(Comparator.comparing(RequestedLine::sku));
        List<SaleLine> taken = new ArrayList<>(ordered.size());
        for (RequestedLine line : ordered) {
            taken.add(new SaleLine(line.sku(), line.quantity(), stockDao.decrementForUpdate(line.sku(), line.quantity())));
        }
        saleDao.saveLines(saleId, taken);
        return DeductOutcome.applied(saleId, taken);
    }

    /**
     * The compensation: put back exactly what {@link #deduct} took for this sale id. If
     * the deduct never arrived, record the sale as reverted so a late one is refused.
     * Restoring twice puts nothing back the second time.
     *
     * @return the units returned to stock
     */
    @Transactional
    public int restore(String saleId) {
        if (saleId == null || saleId.isBlank()) {
            throw new InvalidRequestException("saleId is required");
        }
        if (saleDao.insertIfAbsent(saleId, SaleStatus.REVERTED)) {
            return 0;
        }
        SaleRecord sale = saleDao.findForUpdate(saleId).orElseThrow();
        if (sale.status() == SaleStatus.REVERTED) {
            return 0;
        }
        int units = 0;
        for (SaleLine line : sale.lines()) {   // SKU order, as stored
            if (line.applied() > 0) {
                stockDao.incrementForUpdate(line.sku(), line.applied());
                units += line.applied();
            }
        }
        saleDao.markReverted(saleId);
        return units;
    }
}
