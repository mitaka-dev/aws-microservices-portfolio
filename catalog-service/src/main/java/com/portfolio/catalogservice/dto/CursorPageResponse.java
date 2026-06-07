package com.portfolio.catalogservice.dto;

import java.util.List;

public record CursorPageResponse<T>(List<T> items, String nextCursor) {}
