package com.portfolio.orderservice.dto;

import com.portfolio.orderservice.model.Order;
import com.portfolio.orderservice.model.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
    UUID id,
    String userId,
    OrderStatus status,
    List<OrderItemResponse> items,
    BigDecimal totalAmount,
    Instant createdAt
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
            order.getId(),
            order.getUserId(),
            order.getStatus(),
            order.getItems().stream().map(OrderItemResponse::from).toList(),
            order.getTotalAmount(),
            order.getCreatedAt()
        );
    }
}
