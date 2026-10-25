package com.supermarket.transaction;

import com.supermarket.transaction.domain.BasketLine;

import java.util.List;

/**
 * The checkout saga's view of the inventory service: the stock step and its
 * compensation. Both are idempotent by sale id on the inventory side. Any failure —
 * down, timed out, cancelled — is thrown; the saga cannot tell "never arrived" from
 * "committed but the answer was lost", which is exactly why compensation exists.
 */
interface StockClient {

    /**
     * Take the stock for every line.
     *
     * @return true if inventory applied it; false if it refused because the sale id had
     *         already been restored
     */
    boolean deduct(String saleId, List<BasketLine> lines);

    /** Put back whatever {@link #deduct} took for this sale id, or record that it must never take it. */
    int restore(String saleId);
}
