package com.portfolio.orderservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record CreateOrderRequest(
    @NotBlank String userId,
    @NotEmpty @Valid List<OrderItemRequest> items,
    String paymentMethod
) {
    public String paymentMethod() {
        return paymentMethod != null ? paymentMethod : "CREDIT_CARD";
    }
}
