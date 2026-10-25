import com.supermarket.contracts.CatalogItem;
import com.supermarket.contracts.CatalogServiceGrpc;
import com.supermarket.contracts.GetItemRequest;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.stub.StreamObserver;

import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;

/**
 * Times one gRPC call at a time, in its own JVM, over loopback.
 *
 *   server <port>                                       a CatalogService whose GetItem answers a constant at once
 *   client <label> <target> <sku> <warmup> <seconds> <pauseMicros>
 *
 * Against the dummy server the time is the call alone (serialise, loopback, deserialise, thread hand-offs
 * on both sides); against the real catalog-service it also includes that service's chassis and one
 * primary-key lookup in catalog-db.
 */
public class HopProbe {

    public static void main(String[] args) throws Exception {
        if (args[0].equals("server")) server(Integer.parseInt(args[1]));
        else client(args);
    }

    static void server(int port) throws Exception {
        CatalogItem item = CatalogItem.newBuilder().setSku("SKU-000500").setName("Item 500").setPrice(5.0).build();
        Server server = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
                .addService(new CatalogServiceGrpc.CatalogServiceImplBase() {
                    @Override
                    public void getItem(GetItemRequest request, StreamObserver<CatalogItem> response) {
                        response.onNext(item);
                        response.onCompleted();
                    }
                })
                .build().start();
        System.out.println("dummy server on " + port);
        server.awaitTermination();
    }

    static void client(String[] a) {
        String label = a[1], target = a[2], sku = a[3];
        int warmup = Integer.parseInt(a[4]);
        long seconds = Long.parseLong(a[5]);
        long pauseNanos = Long.parseLong(a[6]) * 1_000;

        ManagedChannel channel = Grpc.newChannelBuilder(target, InsecureChannelCredentials.create()).build();
        CatalogServiceGrpc.CatalogServiceBlockingStub stub = CatalogServiceGrpc.newBlockingStub(channel);
        GetItemRequest request = GetItemRequest.newBuilder().setSku(sku).build();
        for (int i = 0; i < warmup; i++) stub.getItem(request);

        long[] t = new long[4_000_000];
        int n = 0;
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < end && n < t.length) {
            long start = System.nanoTime();
            stub.getItem(request);
            t[n++] = System.nanoTime() - start;
            if (pauseNanos > 0) LockSupport.parkNanos(pauseNanos);
        }
        channel.shutdownNow();

        Arrays.sort(t, 0, n);
        double mean = Arrays.stream(t, 0, n).average().orElse(0) / 1e6;
        System.out.printf("%-40s n=%7d  mean %6.3f  p50 %6.3f  p90 %6.3f  p99 %6.3f  p99.9 %7.3f  max %7.3f ms%n",
                label, n, mean, pct(t, n, 0.50), pct(t, n, 0.90), pct(t, n, 0.99), pct(t, n, 0.999), t[n - 1] / 1e6);
    }

    static double pct(long[] sorted, int n, double p) {
        return sorted[Math.max(0, (int) Math.ceil(p * n) - 1)] / 1e6;
    }
}
