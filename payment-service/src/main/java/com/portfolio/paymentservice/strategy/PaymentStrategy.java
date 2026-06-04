package com.portfolio.paymentservice.strategy;

import com.portfolio.proto.payment.PaymentRequest;

public interface PaymentStrategy {
    PaymentResult process(PaymentRequest request);
}
