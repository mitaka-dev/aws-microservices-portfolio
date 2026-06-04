package com.portfolio.orderservice.messaging;

import java.math.BigDecimal;

public record OrderConfirmedEvent(String orderId, String userId, BigDecimal totalAmount) {}
