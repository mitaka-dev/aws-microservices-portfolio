package com.portfolio.orderservice.messaging;

import com.portfolio.orderservice.grpc.CatalogGrpcClient;
import com.portfolio.orderservice.model.OrderStatus;
import com.portfolio.orderservice.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final OrderRepository orderRepository;
    private final CatalogGrpcClient catalogGrpcClient;

    public OrderEventListener(OrderRepository orderRepository, CatalogGrpcClient catalogGrpcClient) {
        this.orderRepository = orderRepository;
        this.catalogGrpcClient = catalogGrpcClient;
    }

    @Transactional
    public void handleOrderCreated(OrderCreatedEvent event) {
        log.info("Processing OrderCreated for orderId={}", event.orderId());

        boolean allSucceeded = event.items().stream().allMatch(item -> {
            try {
                boolean success = catalogGrpcClient.decrementStock(item.productId(), item.quantity()).getSuccess();
                if (!success) {
                    log.warn("Decrement stock returned false for productId={}", item.productId());
                }
                return success;
            } catch (Exception e) {
                log.error("gRPC decrement failed for productId={}", item.productId(), e);
                return false;
            }
        });

        orderRepository.findById(UUID.fromString(event.orderId())).ifPresent(order -> {
            order.setStatus(allSucceeded ? OrderStatus.CONFIRMED : OrderStatus.FAILED);
            orderRepository.save(order);
            log.info("Order {} updated to {}", event.orderId(), order.getStatus());
        });
    }
}
