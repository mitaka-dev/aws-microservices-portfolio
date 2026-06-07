package com.portfolio.catalogservice.grpc;

import com.portfolio.catalogservice.service.CatalogService;
import com.portfolio.proto.catalog.CatalogServiceGrpc;
import com.portfolio.proto.catalog.DecrementStockRequest;
import com.portfolio.proto.catalog.DecrementStockResponse;
import com.portfolio.proto.catalog.GetProductRequest;
import com.portfolio.proto.catalog.IncrementStockRequest;
import com.portfolio.proto.catalog.IncrementStockResponse;
import com.portfolio.proto.catalog.ProductResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CatalogGrpcService extends CatalogServiceGrpc.CatalogServiceImplBase {

    private final CatalogService catalogService;

    public CatalogGrpcService(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Override
    public void getProduct(GetProductRequest request, StreamObserver<ProductResponse> responseObserver) {
        try {
            var item = catalogService.getItem(request.getProductId());
            var response = ProductResponse.newBuilder()
                .setProductId(item.id())
                .setName(item.name())
                .setPrice(item.price().doubleValue())
                .setStock(item.stock())
                .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (ResponseStatusException e) {
            responseObserver.onError(grpcStatus(e).asRuntimeException());
        }
    }

    @Override
    public void decrementStock(DecrementStockRequest request,
                               StreamObserver<DecrementStockResponse> responseObserver) {
        try {
            boolean success = catalogService.decrementStock(request.getProductId(), request.getQuantity());
            var item = catalogService.getItem(request.getProductId());
            responseObserver.onNext(DecrementStockResponse.newBuilder()
                .setSuccess(success)
                .setRemainingStock(item.stock())
                .build());
            responseObserver.onCompleted();
        } catch (ResponseStatusException e) {
            responseObserver.onError(grpcStatus(e).asRuntimeException());
        }
    }

    @Override
    public void incrementStock(IncrementStockRequest request,
                               StreamObserver<IncrementStockResponse> responseObserver) {
        try {
            int remaining = catalogService.incrementStock(request.getProductId(), request.getQuantity());
            responseObserver.onNext(IncrementStockResponse.newBuilder()
                .setSuccess(true)
                .setRemainingStock(remaining)
                .build());
            responseObserver.onCompleted();
        } catch (ResponseStatusException e) {
            responseObserver.onError(grpcStatus(e).asRuntimeException());
        }
    }

    private Status grpcStatus(ResponseStatusException e) {
        return e.getStatusCode() == HttpStatus.NOT_FOUND
            ? Status.NOT_FOUND.withDescription(e.getReason())
            : Status.INTERNAL.withDescription(e.getMessage());
    }
}
