package com.portfolio.paymentservice.strategy;

public record PaymentResult(boolean success, String failureReason) {

    public static PaymentResult ofSuccess() {
        return new PaymentResult(true, "");
    }

    public static PaymentResult ofFailure(String reason) {
        return new PaymentResult(false, reason);
    }
}
