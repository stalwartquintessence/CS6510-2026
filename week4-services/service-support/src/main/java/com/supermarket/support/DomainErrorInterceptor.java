package com.supermarket.support;

import com.supermarket.contracts.RpcErrors;
import com.supermarket.domain.error.DomainException;
import com.supermarket.domain.error.EmptyBasketException;
import com.supermarket.domain.error.InvalidRequestException;
import com.supermarket.domain.error.ItemNotFoundException;
import com.supermarket.domain.error.TransactionNotFoundException;
import com.supermarket.domain.error.TransactionNotOpenException;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * The gRPC counterpart of week 3's {@code GlobalExceptionHandler}: the one place
 * on the server side that knows how a {@link DomainException} looks on the wire.
 *
 * <p>Service methods just throw. This turns the exception into a gRPC status plus
 * the contract's error code in a trailer (see {@link RpcErrors}); the gateway
 * turns that into the HTTP status. Neither side's business code names the other
 * side's protocol.
 */
class DomainErrorInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DomainErrorInterceptor.class);

    private static final Map<Class<? extends DomainException>, Status.Code> CODE_BY_TYPE = Map.of(
            InvalidRequestException.class, Status.Code.INVALID_ARGUMENT,
            ItemNotFoundException.class, Status.Code.NOT_FOUND,
            TransactionNotFoundException.class, Status.Code.NOT_FOUND,
            TransactionNotOpenException.class, Status.Code.FAILED_PRECONDITION,
            EmptyBasketException.class, Status.Code.FAILED_PRECONDITION);

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(
            ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
        return new SimpleForwardingServerCallListener<>(next.startCall(call, headers)) {
            // Unary methods run inside onHalfClose, once the request message has arrived.
            @Override
            public void onHalfClose() {
                try {
                    super.onHalfClose();
                } catch (DomainException e) {
                    Status.Code code = CODE_BY_TYPE.getOrDefault(e.getClass(), Status.Code.INVALID_ARGUMENT);
                    close(call, code, e.errorCode(), e.getMessage());
                } catch (RuntimeException e) {
                    log.error("Unhandled failure in {}", call.getMethodDescriptor().getFullMethodName(), e);
                    close(call, Status.Code.INTERNAL, "INTERNAL_ERROR", String.valueOf(e.getMessage()));
                }
            }
        };
    }

    private static void close(ServerCall<?, ?> call, Status.Code code, String errorCode, String message) {
        Metadata trailers = new Metadata();
        trailers.put(RpcErrors.ERROR_CODE, errorCode);
        call.close(code.toStatus().withDescription(message), trailers);
    }
}
