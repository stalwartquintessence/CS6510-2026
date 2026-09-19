package com.supermarket.service;

import com.supermarket.dto.Dtos.CatalogItem;
import com.supermarket.dto.Dtos.CatalogResponse;
import com.supermarket.repository.InventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Serves the full item catalog (SKU, name, price) from the inventory table. */
@Service
public class CatalogService {

    private final InventoryRepository inventoryRepository;

    public CatalogService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    @Transactional(readOnly = true)
    public CatalogResponse catalog() {
        List<CatalogItem> items = inventoryRepository.findAll(
                        org.springframework.data.domain.Sort.by("sku")).stream()
                .map(i -> new CatalogItem(i.getSku(), i.getName(), i.getPrice()))
                .toList();
        return new CatalogResponse(items);
    }
}
