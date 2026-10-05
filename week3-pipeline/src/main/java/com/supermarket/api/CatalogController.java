package com.supermarket.api;

import com.supermarket.api.dto.CatalogDtos.CatalogResponse;
import com.supermarket.api.mapper.CatalogDtoMapper;
import com.supermarket.transaction.catalog.CatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/items")
    public CatalogResponse items() {
        return CatalogDtoMapper.toResponse(catalogService.catalog());
    }
}
