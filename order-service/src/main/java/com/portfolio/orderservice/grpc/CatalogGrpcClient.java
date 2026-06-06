package com.portfolio.orderservice.grpc;

import com.portfolio.proto.catalog.CatalogServiceGrpc;
import com.portfolio.proto.catalog.DecrementStockRequest;
import com.portfolio.proto.catalog.DecrementStockResponse;
import com.portfolio.proto.catalog.IncrementStockRequest;
import com.portfolio.proto.catalog.IncrementStockResponse;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class CatalogGrpcClient {

    private final ManagedChannel channel;
    private final CatalogServiceGrpc.CatalogServiceBlockingStub stub;

    public CatalogGrpcClient(
        @Value("${catalog.grpc.host:catalog-service.internal.local}") String host,
        @Value("${catalog.grpc.port:9090}") int port
    ) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .build();
        this.stub = CatalogServiceGrpc.newBlockingStub(channel);
    }

    // No @Retry — decrementStock is non-idempotent; retrying would double-decrement stock.
    @CircuitBreaker(name = "catalog-grpc-decrement-stock", fallbackMethod = "decrementStockFallback")
    @Bulkhead(name = "catalog-grpc-decrement-stock", type = Bulkhead.Type.SEMAPHORE)
    public DecrementStockResponse decrementStock(String productId, int quantity) {
        return stub.withDeadlineAfter(2, TimeUnit.SECONDS)
            .decrementStock(DecrementStockRequest.newBuilder()
                .setProductId(productId)
                .setQuantity(quantity)
                .build());
    }

    // No @Retry — incrementStock is not idempotent without orderId-based deduplication.
    // Failed attempts are persisted to saga_compensation_steps and retried by OrderRecoveryJob.
    @CircuitBreaker(name = "catalog-grpc-increment-stock", fallbackMethod = "incrementStockFallback")
    @Bulkhead(name = "catalog-grpc-decrement-stock", type = Bulkhead.Type.SEMAPHORE)
    public IncrementStockResponse incrementStock(String productId, int quantity) {
        return stub.withDeadlineAfter(2, TimeUnit.SECONDS)
            .incrementStock(IncrementStockRequest.newBuilder()
                .setProductId(productId)
                .setQuantity(quantity)
                .build());
    }

    private DecrementStockResponse decrementStockFallback(String productId, int quantity, Throwable t) {
        throw Status.UNAVAILABLE
            .withDescription("catalog-service unavailable")
            .withCause(t)
            .asRuntimeException();
    }

    private IncrementStockResponse incrementStockFallback(String productId, int quantity, Throwable t) {
        throw Status.UNAVAILABLE
            .withDescription("catalog-service unavailable")
            .withCause(t)
            .asRuntimeException();
    }

    @PreDestroy
    public void shutdown() {
        channel.shutdown();
        try {
            channel.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
