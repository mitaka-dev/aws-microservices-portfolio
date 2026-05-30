package com.portfolio.orderservice.grpc;

import com.portfolio.proto.catalog.CatalogServiceGrpc;
import com.portfolio.proto.catalog.DecrementStockRequest;
import com.portfolio.proto.catalog.DecrementStockResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
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

    public DecrementStockResponse decrementStock(String productId, int quantity) {
        return stub.decrementStock(DecrementStockRequest.newBuilder()
            .setProductId(productId)
            .setQuantity(quantity)
            .build());
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
