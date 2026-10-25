package com.supermarket.inventory.model;

/**
 * What a deduction actually did to one SKU. {@code applied} can be less than
 * {@code requested}: stock is floored at zero, as in weeks 1–4, so demand beyond
 * supply is sold but not decremented. Compensation puts back {@code applied}, never
 * {@code requested}, or it would create stock that never existed.
 */
public record SaleLine(String sku, int requested, int applied) {
}
