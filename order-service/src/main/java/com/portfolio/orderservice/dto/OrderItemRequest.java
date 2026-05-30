package com.portfolio.orderservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record OrderItemRequest(
    @NotBlank String productId,
    @Positive int quantity,
    @Positive BigDecimal unitPrice
) {}
