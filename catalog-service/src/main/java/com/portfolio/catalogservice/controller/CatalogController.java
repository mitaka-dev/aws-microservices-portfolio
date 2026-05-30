package com.portfolio.catalogservice.controller;

import com.portfolio.catalogservice.dto.CatalogItemResponse;
import com.portfolio.catalogservice.dto.CreateCatalogItemRequest;
import com.portfolio.catalogservice.service.CatalogService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/catalog")
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogItemResponse createItem(@Valid @RequestBody CreateCatalogItemRequest request) {
        return catalogService.createItem(request);
    }

    @GetMapping("/{id}")
    public CatalogItemResponse getItem(@PathVariable String id) {
        return catalogService.getItem(id);
    }

    @GetMapping
    public List<CatalogItemResponse> listItems() {
        return catalogService.listItems();
    }
}
