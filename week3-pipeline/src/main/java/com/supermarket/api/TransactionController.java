package com.supermarket.api;

import com.supermarket.api.dto.TransactionDtos.ReceiptResponse;
import com.supermarket.api.dto.TransactionDtos.ScanItemRequest;
import com.supermarket.api.dto.TransactionDtos.ScanResponse;
import com.supermarket.api.dto.TransactionDtos.StartTransactionRequest;
import com.supermarket.api.dto.TransactionDtos.TransactionResponse;
import com.supermarket.api.mapper.TransactionDtoMapper;
import com.supermarket.transaction.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    /**
     * Bodies stay {@code required = false} so a missing body reaches the service
     * as a null field and becomes a 400 INVALID_REQUEST, rather than Spring
     * rejecting it with its own error shape.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse start(@Valid @RequestBody(required = false) StartTransactionRequest request) {
        String stationId = request != null ? request.stationId() : null;
        return TransactionDtoMapper.toResponse(transactionService.start(stationId));
    }

    @PostMapping("/{transactionId}/items")
    public ScanResponse scan(@PathVariable String transactionId,
                             @Valid @RequestBody(required = false) ScanItemRequest request) {
        String sku = request != null ? request.sku() : null;
        return TransactionDtoMapper.toResponse(transactionService.scan(transactionId, sku));
    }

    /**
     * The contract declares no request body here, but the shared load client
     * posts a literal <code>{}</code> — so no body parameter is declared and
     * anything sent is simply ignored.
     */
    @PostMapping("/{transactionId}/complete")
    public ReceiptResponse complete(@PathVariable String transactionId) {
        return TransactionDtoMapper.toResponse(transactionService.complete(transactionId));
    }

    @GetMapping("/{transactionId}")
    public TransactionResponse status(@PathVariable String transactionId) {
        return TransactionDtoMapper.toResponse(transactionService.getStatus(transactionId));
    }
}
