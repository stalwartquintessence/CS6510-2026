package com.supermarket.transaction;

import com.supermarket.support.error.InvalidRequestException;
import com.supermarket.transaction.domain.Basket;
import com.supermarket.transaction.domain.ItemSnapshot;
import com.supermarket.transaction.model.BasketView;
import com.supermarket.transaction.model.ReceiptView;
import com.supermarket.transaction.model.ScanView;
import org.springframework.stereotype.Service;

/**
 * The checkout lifecycle. Start and scan are one local transaction each, as in
 * week 4; completion is the {@link CheckoutSaga}, because the stock it decrements now
 * lives in the inventory service's database.
 *
 * <p>Nothing here is {@code @Transactional}: the transactions are {@link BasketStore}'s,
 * and this class decides what happens <em>between</em> them — in particular, the catalog
 * lookup on a scan happens before the basket's transaction opens, so a cache miss never
 * holds a database connection across a network call.
 */
@Service
class DefaultTransactionService implements TransactionService {

    private final BasketStore baskets;
    private final CatalogCache catalog;
    private final CheckoutSaga checkout;

    DefaultTransactionService(BasketStore baskets, CatalogCache catalog, CheckoutSaga checkout) {
        this.baskets = baskets;
        this.catalog = catalog;
        this.checkout = checkout;
    }

    @Override
    public BasketView start(String stationId) {
        if (stationId == null || stationId.isBlank()) {
            throw new InvalidRequestException("stationId is required");
        }
        return toView(baskets.open(stationId));
    }

    @Override
    public ScanView scan(String transactionId, String sku) {
        if (sku == null || sku.isBlank()) {
            throw new InvalidRequestException("sku is required");
        }
        ItemSnapshot item = catalog.item(sku);
        Basket basket = baskets.addUnit(transactionId, item);
        return new ScanView(
                basket.transactionId(),
                item.sku(),
                item.name(),
                item.price(),
                basket.itemCount(),
                basket.total());
    }

    @Override
    public ReceiptView complete(String transactionId) {
        return checkout.complete(transactionId);
    }

    @Override
    public BasketView getStatus(String transactionId) {
        return toView(baskets.find(transactionId));
    }

    /** A saga in flight shows as OPEN: the contract's enum has no word for it. */
    private static BasketView toView(Basket basket) {
        return new BasketView(
                basket.transactionId(),
                basket.stationId(),
                basket.status().visible().name(),
                basket.itemCount(),
                basket.total(),
                basket.startedAt());
    }
}
