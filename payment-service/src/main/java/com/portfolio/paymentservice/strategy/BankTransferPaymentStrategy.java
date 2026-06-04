package com.portfolio.paymentservice.strategy;

import com.portfolio.proto.payment.PaymentRequest;
import org.springframework.stereotype.Component;

@Component
public class BankTransferPaymentStrategy implements PaymentStrategy {

    @Override
    public PaymentResult process(PaymentRequest request) {
        return PaymentResult.ofSuccess();
    }
}
