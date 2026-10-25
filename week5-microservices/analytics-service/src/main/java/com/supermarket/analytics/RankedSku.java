package com.supermarket.analytics;

/** One entry of a ranking before catalog names are attached. */
record RankedSku(String sku, int scanCount, int rank) {
}
