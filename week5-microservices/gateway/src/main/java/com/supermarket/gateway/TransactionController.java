package com.supermarket.gateway;

import com.supermarket.contracts.CompleteRequest;
import com.supermarket.contracts.GetStatusRequest;
import com.supermarket.contracts.ScanRequest;
import com.supermarket.contracts.StartRequest;
import com.supermarket.gateway.dto.TransactionDtos.ReceiptResponse;
import com.supermarket.gateway.dto.TransactionDtos.ScanItemRequest;
import com.supermarket.gateway.dto.TransactionDtos.ScanResponse;
import com.supermarket.gateway.dto.TransactionDtos.StartTransactionRequest;
import com.supermarket.gateway.dto.TransactionDtos.TransactionResponse;
import com.supermarket.gateway.rpc.ServiceClients;
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

    private final ServiceClients services;

    public TransactionController(ServiceClients services) {
        this.services = services;
    }

    /**
     * Bodies stay {@code required = false} so a missing body reaches the transaction
     * service as an empty field and becomes a 400 INVALID_REQUEST, rather than Spring
     * rejecting it with its own error shape.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse start(@Valid @RequestBody(required = false) StartTransactionRequest request) {
        String stationId = request != null && request.stationId() != null ? request.stationId() : "";
        return GatewayMapper.toResponse(
                services.transaction().start(StartRequest.newBuilder().setStationId(stationId).build()));
    }

    @PostMapping("/{transactionId}/items")
    public ScanResponse scan(@PathVariable String transactionId,
                             @Valid @RequestBody(required = false) ScanItemRequest request) {
        String sku = request != null && request.sku() != null ? request.sku() : "";
        return GatewayMapper.toResponse(services.transaction().scan(
                ScanRequest.newBuilder().setTransactionId(transactionId).setSku(sku).build()));
    }

    /**
     * The contract declares no request body here, but the shared load client posts a
     * literal <code>{}</code> — so no body parameter is declared and anything sent is ignored.
     */
    @PostMapping("/{transactionId}/complete")
    public ReceiptResponse complete(@PathVariable String transactionId) {
        return GatewayMapper.toResponse(services.transaction().complete(
                CompleteRequest.newBuilder().setTransactionId(transactionId).build()));
    }

    @GetMapping("/{transactionId}")
    public TransactionResponse status(@PathVariable String transactionId) {
        return GatewayMapper.toResponse(services.transaction().getStatus(
                GetStatusRequest.newBuilder().setTransactionId(transactionId).build()));
    }
}
