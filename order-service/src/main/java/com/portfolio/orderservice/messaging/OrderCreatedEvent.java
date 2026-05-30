package com.portfolio.orderservice.messaging;

import java.util.List;

public record OrderCreatedEvent(String orderId, String userId, List<OrderItemEvent> items) {}
