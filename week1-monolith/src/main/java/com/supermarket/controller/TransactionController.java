package com.supermarket.controller;

import com.supermarket.dto.Dtos.ReceiptResponse;
import com.supermarket.dto.Dtos.ScanItemRequest;
import com.supermarket.dto.Dtos.ScanResponse;
import com.supermarket.dto.Dtos.StartTransactionRequest;
import com.supermarket.dto.Dtos.TransactionResponse;
import com.supermarket.service.TransactionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse start(@RequestBody(required = false) StartTransactionRequest request) {
        String stationId = request != null ? request.stationId() : null;
        return transactionService.start(stationId);
    }

    @PostMapping("/{transactionId}/items")
    public ScanResponse scan(@PathVariable String transactionId,
                             @RequestBody(required = false) ScanItemRequest request) {
        String sku = request != null ? request.sku() : null;
        return transactionService.scan(transactionId, sku);
    }

    @PostMapping("/{transactionId}/complete")
    public ReceiptResponse complete(@PathVariable String transactionId) {
        return transactionService.complete(transactionId);
    }

    @GetMapping("/{transactionId}")
    public TransactionResponse status(@PathVariable String transactionId) {
        return transactionService.getStatus(transactionId);
    }
}
