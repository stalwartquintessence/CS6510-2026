package com.supermarket.api.dto;

import java.util.List;

/** Wire shapes for {@code GET /analytics/popular-items}. */
public final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    public record PopularItemDto(String sku, String name, int scanCount, int rank) {
    }

    public record PopularItemsResponse(
            int windowSize,
            int slideInterval,
            long windowStart,
            long windowEnd,
            String computedAt,
            List<PopularItemDto> items) {
    }
}
