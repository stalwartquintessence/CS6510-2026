package com.supermarket.inventory.persistence;

import com.supermarket.inventory.model.SaleLine;
import com.supermarket.inventory.model.SaleRecord;
import com.supermarket.inventory.model.SaleStatus;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
class JpaSaleDao implements SaleDao {

    private final SaleJpaRepository sales;
    private final SaleLineJpaRepository lines;

    JpaSaleDao(SaleJpaRepository sales, SaleLineJpaRepository lines) {
        this.sales = sales;
        this.lines = lines;
    }

    @Override
    public boolean insertIfAbsent(String saleId, SaleStatus status) {
        return sales.insertIfAbsent(saleId, status.name()) == 1;
    }

    @Override
    public Optional<SaleRecord> findForUpdate(String saleId) {
        return sales.findBySaleIdForUpdate(saleId).map(sale -> new SaleRecord(
                sale.getSaleId(),
                sale.getStatus(),
                lines.findBySaleIdOrderBySkuAsc(saleId).stream()
                        .map(line -> new SaleLine(line.getSku(), line.getRequested(), line.getApplied()))
                        .toList()));
    }

    @Override
    public void saveLines(String saleId, List<SaleLine> saleLines) {
        List<SaleLineEntity> entities = new ArrayList<>(saleLines.size());
        for (SaleLine line : saleLines) {
            entities.add(new SaleLineEntity(saleId, line.sku(), line.requested(), line.applied()));
        }
        lines.saveAll(entities);
    }

    @Override
    public void markReverted(String saleId) {
        // A first-level cache hit on the entity findForUpdate loaded and locked; the
        // status change is flushed by dirty checking at commit.
        sales.findById(saleId).ifPresent(sale -> sale.setStatus(SaleStatus.REVERTED));
    }
}
