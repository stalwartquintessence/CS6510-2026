package com.supermarket.support;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a service's gRPC server for the life of the Spring context.
 *
 * <p>Service methods execute on a fixed worker pool rather than gRPC's default
 * unbounded one. They block on JDBC, so the pool is the service's request
 * concurrency ceiling — the counterpart of the Tomcat thread pool in weeks 1–3.
 *
 * <p>gRPC's own threads are daemons, so on its own a service with no web server
 * would exit as soon as startup finished; a non-daemon thread parked in
 * {@code awaitTermination} keeps the JVM alive until shutdown.
 */
class GrpcServerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);

    private final Server server;
    private final ExecutorService workers;
    private final int port;
    private volatile boolean running;

    GrpcServerLifecycle(GrpcServerProperties properties, List<BindableService> services) {
        this.port = properties.port();
        AtomicInteger workerIds = new AtomicInteger();
        this.workers = Executors.newFixedThreadPool(properties.threads(),
                runnable -> new Thread(runnable, "grpc-worker-" + workerIds.incrementAndGet()));
        NettyServerBuilder builder = NettyServerBuilder.forPort(port).executor(workers);
        DomainErrorInterceptor errors = new DomainErrorInterceptor();
        for (BindableService service : services) {
            builder.addService(ServerInterceptors.intercept(service, errors));
        }
        this.server = builder.build();
    }

    @Override
    public void start() {
        try {
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start gRPC server on port " + port, e);
        }
        running = true;
        Thread keepAlive = new Thread(() -> {
            try {
                server.awaitTermination();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "grpc-keepalive");
        keepAlive.setDaemon(false);
        keepAlive.start();
        log.info("gRPC server listening on port {}", port);
    }

    @Override
    public void stop() {
        server.shutdown();
        try {
            if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                server.shutdownNow();
            }
        } catch (InterruptedException e) {
            server.shutdownNow();
            Thread.currentThread().interrupt();
        }
        workers.shutdownNow();
        running = false;
        log.info("gRPC server stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Last in, first out: accept calls only once everything else is up, stop accepting before it goes down. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
