package com.portfolio.catalogservice.service;

import com.portfolio.catalogservice.dto.CatalogItemResponse;
import com.portfolio.catalogservice.dto.CreateCatalogItemRequest;
import com.portfolio.catalogservice.model.CatalogItem;
import com.portfolio.catalogservice.repository.CatalogItemRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class CatalogService {

    private final CatalogItemRepository repository;
    private final MeterRegistry meterRegistry;

    public CatalogService(CatalogItemRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
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

    public List<CatalogItemResponse> listItems() {
        return repository.findAll().stream()
            .map(CatalogItemResponse::from)
            .toList();
    }

    @CacheEvict(value = "catalog", key = "#id")
    public boolean decrementStock(String id, int qty) {
        if (!repository.findById(id).isPresent()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found");
        }
        return repository.decrementStock(id, qty);
    }
}
