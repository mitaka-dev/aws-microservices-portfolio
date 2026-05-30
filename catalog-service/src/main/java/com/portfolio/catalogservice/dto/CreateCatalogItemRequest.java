package com.portfolio.catalogservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record CreateCatalogItemRequest(
    @NotBlank String name,
    @NotBlank String category,
    @Positive BigDecimal price,
    @PositiveOrZero int stock
) {}
