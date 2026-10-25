package com.supermarket.inventory;

import java.util.Comparator;

/**
 * The order in which a sale locks its stock rows ({@code supermarket.inventory.lock-order}).
 *
 * <p>Any one fixed order rules out deadlock between concurrent sales, which is all weeks
 * 1–5 needed it for, and all of them use {@link #SKU_ASCENDING}. The order does decide
 * how long each lock is held, though: a sale holds every row it has locked until it
 * commits, so the row it locks <em>first</em> is held longest. Under the load client's
 * Zipf workload the hottest item is {@code SKU-000001}, which sorts first, so ascending
 * order makes the most contended lock also the longest held. {@link #SKU_DESCENDING}
 * takes it last instead. It is an experiment, off by default, so the week-over-week
 * comparison stays about the architecture (see ARCHITECTURE.md).
 *
 * <p>Descending SKU order only means "hottest last" because the catalog's numbering
 * happens to match the workload's popularity ranking. Ordering by measured popularity
 * instead would not be safe as it stands: the order must be the same for every
 * concurrent sale, and a ranking that moves would let two sales disagree.
 */
public enum LockOrder {

    SKU_ASCENDING(Comparator.naturalOrder()),
    SKU_DESCENDING(Comparator.reverseOrder());

    private final Comparator<String> skus;

    LockOrder(Comparator<String> skus) {
        this.skus = skus;
    }

    Comparator<String> skus() {
        return skus;
    }
}
