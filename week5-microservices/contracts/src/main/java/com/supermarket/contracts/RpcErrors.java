package com.supermarket.contracts;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.time.Instant;

/**
 * How a business-rule failure crosses the wire.
 *
 * <p>gRPC has its own status vocabulary (NOT_FOUND, FAILED_PRECONDITION, …), which
 * says <em>what kind</em> of failure it was but not <em>which</em> rule. The
 * contract's machine-readable error code (UNKNOWN_SKU, TRANSACTION_NOT_OPEN, …)
 * therefore travels in a trailer next to the status, so the gateway can rebuild
 * the exact HTTP error body week 3 produced.
 */
public final class RpcErrors {

    public static final Metadata.Key<String> ERROR_CODE =
            Metadata.Key.of("x-error-code", Metadata.ASCII_STRING_MARSHALLER);

    private RpcErrors() {
    }

    public static StatusRuntimeException failure(Status.Code code, String errorCode, String message) {
        Metadata trailers = new Metadata();
        trailers.put(ERROR_CODE, errorCode);
        return code.toStatus().withDescription(message).asRuntimeException(trailers);
    }

    /** The contract error code carried by a failed call, or {@code null} if the server sent none. */
    public static String errorCodeOf(StatusRuntimeException e) {
        Metadata trailers = Status.trailersFromThrowable(e);
        return trailers == null ? null : trailers.get(ERROR_CODE);
    }

    public static com.google.protobuf.Timestamp toTimestamp(Instant instant) {
        return com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    public static Instant toInstant(com.google.protobuf.Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }
}
