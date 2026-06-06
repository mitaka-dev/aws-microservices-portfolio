package com.portfolio.catalogservice.dto;

import com.portfolio.catalogservice.model.CatalogItem;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.List;
import java.util.Map;

public record CatalogResultPage(List<CatalogItem> items, Map<String, AttributeValue> lastKey) {}
