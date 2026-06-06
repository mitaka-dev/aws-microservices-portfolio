package com.portfolio.catalogservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.catalogservice.dto.CatalogItemResponse;
import com.portfolio.catalogservice.dto.CreateCatalogItemRequest;
import com.portfolio.catalogservice.dto.CursorPageResponse;
import com.portfolio.catalogservice.model.CatalogItem;
import com.portfolio.catalogservice.repository.CatalogItemRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CatalogService {

    private final CatalogItemRepository repository;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public CatalogService(CatalogItemRepository repository, MeterRegistry meterRegistry,
                          ObjectMapper objectMapper) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    public CatalogItemResponse createItem(CreateCatalogItemRequest request) {
        var id = UUID.randomUUID().toString();
        var item = new CatalogItem();
        item.setPk("PRODUCT#" + id);
        item.setSk("PRODUCT#" + id);
        item.setGsi1pk("PRODUCTS");
        item.setGsi1sk("PRODUCT#" + id);
        item.setId(id);
        item.setName(request.name());
        item.setCategory(request.category());
        item.setPrice(request.price());
        item.setStock(request.stock());
        item.setCreatedAt(Instant.now());
        repository.save(item);
        meterRegistry.counter("catalog.items.created.total").increment();
        return CatalogItemResponse.from(item);
    }

    @Cacheable(value = "catalog", key = "#id")
    public CatalogItemResponse getItem(String id) {
        return repository.findById(id)
            .map(CatalogItemResponse::from)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
    }

    public CursorPageResponse<CatalogItemResponse> listItems(int size, String cursor) {
        Map<String, AttributeValue> exclusiveStartKey = decodeCursor(cursor);
        var page = repository.findPage(size, exclusiveStartKey);
        String nextCursor = encodeCursor(page.lastKey());
        var items = page.items().stream().map(CatalogItemResponse::from).toList();
        return new CursorPageResponse<>(items, nextCursor);
    }

    @CacheEvict(value = "catalog", key = "#id")
    public boolean decrementStock(String id, int qty) {
        if (!repository.findById(id).isPresent()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found");
        }
        return repository.decrementStock(id, qty);
    }

    @CacheEvict(value = "catalog", key = "#id")
    public int incrementStock(String id, int qty) {
        if (!repository.findById(id).isPresent()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found");
        }
        return repository.incrementStock(id, qty);
    }

    private String encodeCursor(Map<String, AttributeValue> lastKey) {
        if (lastKey == null || lastKey.isEmpty()) return null;
        // All key attributes in this schema are String type
        Map<String, String> simple = lastKey.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().s()));
        try {
            byte[] json = objectMapper.writeValueAsBytes(simple);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to encode cursor", e);
        }
    }

    private Map<String, AttributeValue> decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            byte[] json = Base64.getUrlDecoder().decode(cursor);
            Map<String, String> simple = objectMapper.readValue(json,
                new TypeReference<Map<String, String>>() {});
            return simple.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                    e -> AttributeValue.fromS(e.getValue())));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor");
        }
    }
}
