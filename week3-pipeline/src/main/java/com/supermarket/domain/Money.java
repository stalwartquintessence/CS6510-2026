package com.supermarket.domain;

/** Rounding shared by every layer that reports an amount. */
public final class Money {

    private Money() {
    }

    /** Round to two decimal places, matching the contract's money fields. */
    public static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
