package com.supermarket.inventory.model;

import java.util.List;

/** A sale as the ledger stored it. A tombstone has no lines. */
public record SaleRecord(String saleId, SaleStatus status, List<SaleLine> lines) {

    public SaleRecord {
        lines = List.copyOf(lines);
    }
}
