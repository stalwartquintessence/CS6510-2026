package com.supermarket.transaction;

import com.supermarket.transaction.model.BasketView;
import com.supermarket.transaction.model.ReceiptView;
import com.supermarket.transaction.model.ScanView;

/**
 * The checkout lifecycle: start → scan (one unit at a time) → complete.
 *
 * <p>Returns domain views, never API DTOs. Week 1's service returned the wire
 * records directly, which made the service layer depend upward on the web
 * layer's vocabulary.
 */
public interface TransactionService {

    BasketView start(String stationId);

    ScanView scan(String transactionId, String sku);

    ReceiptView complete(String transactionId);

    BasketView getStatus(String transactionId);
}
