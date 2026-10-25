package com.supermarket.inventory.persistence;

import com.supermarket.inventory.model.SaleLine;
import com.supermarket.inventory.model.SaleRecord;
import com.supermarket.inventory.model.SaleStatus;

import java.util.List;
import java.util.Optional;

/** The inventory ledger: which saga steps have been applied or reverted, and what each took. */
public interface SaleDao {

    /**
     * Record a sale id with the given status, unless it is already recorded.
     *
     * @return true if this call created the row, false if the id was already known
     */
    boolean insertIfAbsent(String saleId, SaleStatus status);

    /** The sale and its lines, with the sale row locked for the rest of the transaction. */
    Optional<SaleRecord> findForUpdate(String saleId);

    void saveLines(String saleId, List<SaleLine> lines);

    /** Must be called in the transaction that {@link #findForUpdate locked} the row. */
    void markReverted(String saleId);
}
