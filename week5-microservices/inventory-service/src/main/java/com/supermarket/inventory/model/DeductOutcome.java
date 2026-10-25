package com.supermarket.inventory.model;

import java.util.List;

/**
 * Result of the saga's stock step. {@code applied == false} only when the sale had
 * already been restored (the tombstone case): nothing was deducted, and the
 * orchestrator must treat the step as failed.
 */
public record DeductOutcome(String saleId, boolean applied, List<SaleLine> lines) {

    public DeductOutcome {
        lines = List.copyOf(lines);
    }

    public static DeductOutcome applied(String saleId, List<SaleLine> lines) {
        return new DeductOutcome(saleId, true, lines);
    }

    public static DeductOutcome refused(String saleId) {
        return new DeductOutcome(saleId, false, List.of());
    }
}
