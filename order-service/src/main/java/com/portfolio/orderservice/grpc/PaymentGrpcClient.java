package com.portfolio.orderservice.grpc;

import com.portfolio.proto.payment.PaymentMethod;
import com.portfolio.proto.payment.PaymentRequest;
import com.portfolio.proto.payment.PaymentResponse;
import com.portfolio.proto.payment.PaymentServiceGrpc;
import com.portfolio.proto.payment.RefundRequest;
import com.portfolio.proto.payment.RefundResponse;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class PaymentGrpcClient {

    private final ManagedChannel channel;
    private final PaymentServiceGrpc.PaymentServiceBlockingStub stub;

    public PaymentGrpcClient(
        @Value("${payment.grpc.host:payment-service.internal.local}") String host,
        @Value("${payment.grpc.port:9090}") int port
    ) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .build();
        this.stub = PaymentServiceGrpc.newBlockingStub(channel);
    }

    // @Retry is safe: payment-service deduplicates on order_id (V2 migration adds UNIQUE constraint).
    // A retry after a transient failure returns the existing SUCCESS record, never double-charges.
    @CircuitBreaker(name = "payment-grpc-process", fallbackMethod = "processPaymentFallback")
    @Bulkhead(name = "payment-grpc-process", type = Bulkhead.Type.SEMAPHORE)
    @Retry(name = "payment-grpc-process")
    public PaymentResponse processPayment(String orderId, double amount, String currency, PaymentMethod method) {
        return stub.withDeadlineAfter(2, TimeUnit.SECONDS)
            .processPayment(PaymentRequest.newBuilder()
                .setOrderId(orderId)
                .setAmount(amount)
                .setCurrency(currency)
                .setMethod(method)
                .build());
    }

    // @Retry is safe: payment-service checks if already REFUNDED before writing (idempotent).
    // Extra retries on refund are preferable to losing a refund — money has already been taken.
    @CircuitBreaker(name = "payment-grpc-refund", fallbackMethod = "refundPaymentFallback")
    @Bulkhead(name = "payment-grpc-refund", type = Bulkhead.Type.SEMAPHORE)
    @Retry(name = "payment-grpc-refund")
    public RefundResponse refundPayment(String paymentId, String reason) {
        return stub.withDeadlineAfter(2, TimeUnit.SECONDS)
            .refundPayment(RefundRequest.newBuilder()
                .setPaymentId(paymentId)
                .setReason(reason)
                .build());
    }

    private PaymentResponse processPaymentFallback(String orderId, double amount, String currency,
                                                    PaymentMethod method, Throwable t) {
        throw Status.UNAVAILABLE
            .withDescription("payment-service unavailable")
            .withCause(t)
            .asRuntimeException();
    }

    private RefundResponse refundPaymentFallback(String paymentId, String reason, Throwable t) {
        throw Status.UNAVAILABLE
            .withDescription("payment-service unavailable")
            .withCause(t)
            .asRuntimeException();
    }

    @PreDestroy
    public void shutdown() {
        channel.shutdown();
        try {
            channel.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
