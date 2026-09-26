package com.supermarket.api.mapper;

import com.supermarket.api.dto.CatalogDtos.CatalogItemDto;
import com.supermarket.api.dto.CatalogDtos.CatalogResponse;
import com.supermarket.domain.ItemSnapshot;

import java.util.List;

/** Domain → wire for the catalog endpoint. */
public final class CatalogDtoMapper {

    private CatalogDtoMapper() {
    }

    public static CatalogResponse toResponse(List<ItemSnapshot> items) {
        List<CatalogItemDto> dtos = items.stream()
                .map(i -> new CatalogItemDto(i.sku(), i.name(), i.price()))
                .toList();
        return new CatalogResponse(dtos);
    }
}
