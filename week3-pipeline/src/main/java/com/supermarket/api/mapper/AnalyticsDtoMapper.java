package com.supermarket.api.mapper;

import com.supermarket.analytics.model.PopularItemsView;
import com.supermarket.api.dto.AnalyticsDtos.PopularItemDto;
import com.supermarket.api.dto.AnalyticsDtos.PopularItemsResponse;

import java.util.List;

/** Domain → wire for the popular-items endpoint. */
public final class AnalyticsDtoMapper {

    private AnalyticsDtoMapper() {
    }

    public static PopularItemsResponse toResponse(PopularItemsView view) {
        List<PopularItemDto> items = view.items().stream()
                .map(i -> new PopularItemDto(i.sku(), i.name(), i.scanCount(), i.rank()))
                .toList();
        return new PopularItemsResponse(
                view.windowSize(),
                view.slideInterval(),
                view.windowStart(),
                view.windowEnd(),
                view.computedAt().toString(),
                items);
    }
}
