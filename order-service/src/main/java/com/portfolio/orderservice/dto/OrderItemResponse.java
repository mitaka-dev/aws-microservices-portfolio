package com.portfolio.orderservice.dto;

import com.portfolio.orderservice.model.OrderItem;

import java.math.BigDecimal;

public record OrderItemResponse(
    Long id,
    String productId,
    int quantity,
    BigDecimal unitPrice
) {
    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(item.getId(), item.getProductId(), item.getQuantity(), item.getUnitPrice());
    }
}
