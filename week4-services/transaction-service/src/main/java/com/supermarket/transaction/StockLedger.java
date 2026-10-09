package com.supermarket.transaction;

import com.supermarket.domain.ItemSnapshot;
import com.supermarket.persistence.ItemDao;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The checkout's two touches of the {@code items} table: price lookup at scan
 * time and the stock decrement at completion.
 *
 * <p>These live in the transaction service rather than behind an RPC to the
 * inventory service on purpose. The decrement has to commit or roll back together
 * with the basket becoming COMPLETED; across a service boundary that would need a
 * distributed transaction (or compensation logic), whereas over the shared
 * database it is one {@code @Transactional} method. That is the central trade
 * this architecture makes — see ARCHITECTURE.md.
 */
@Component
class StockLedger {

    private final ItemDao itemDao;

    StockLedger(ItemDao itemDao) {
        this.itemDao = itemDao;
    }

    @Transactional(readOnly = true)
    Optional<ItemSnapshot> findItem(String sku) {
        return itemDao.findBySku(sku);
    }

    /**
     * {@code MANDATORY}: the {@code PESSIMISTIC_WRITE} lock is only held until the
     * surrounding transaction ends, so a caller without one would silently lose the
     * oversell protection. Fail loudly instead (the week 2 fix, kept).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void decrement(String sku, int quantity) {
        itemDao.decrementStockForUpdate(sku, quantity);
    }
}
