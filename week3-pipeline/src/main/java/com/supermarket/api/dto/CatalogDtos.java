package com.supermarket.api.dto;

import java.util.List;

/**
 * Wire shapes for {@code GET /items}.
 *
 * <p>Field names are parsed by name by the shared load client and are fixed by
 * {@code spec/self-checkout-openapi.yaml} — they must not drift.
 */
public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record CatalogItemDto(String sku, String name, double price) {
    }

    public record CatalogResponse(List<CatalogItemDto> items) {
    }
}
