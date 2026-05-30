package com.portfolio.catalogservice.dto;

import com.portfolio.catalogservice.model.CatalogItem;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

public record CatalogItemResponse(
    String id,
    String name,
    String category,
    BigDecimal price,
    int stock,
    Instant createdAt
) implements Serializable {
    public static CatalogItemResponse from(CatalogItem item) {
        return new CatalogItemResponse(
            item.getId(),
            item.getName(),
            item.getCategory(),
            item.getPrice(),
            item.getStock(),
            item.getCreatedAt()
        );
    }
}
