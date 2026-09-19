package com.supermarket.controller;

import com.supermarket.dto.Dtos.CatalogResponse;
import com.supermarket.service.CatalogService;
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
        return catalogService.catalog();
    }
}
