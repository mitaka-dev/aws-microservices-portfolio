package com.portfolio.orderservice.messaging;

import java.math.BigDecimal;

public record OrderItemEvent(String productId, int quantity, BigDecimal unitPrice) {}
