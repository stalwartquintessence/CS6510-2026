package com.supermarket.support.error;

/** The referenced SKU is not in the catalog. */
public class ItemNotFoundException extends DomainException {

    public ItemNotFoundException(String sku) {
        super("UNKNOWN_SKU", "No such SKU: " + sku);
    }
}
