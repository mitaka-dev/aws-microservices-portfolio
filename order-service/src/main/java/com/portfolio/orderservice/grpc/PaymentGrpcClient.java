package com.portfolio.orderservice.grpc;

import com.portfolio.proto.payment.PaymentMethod;
import com.portfolio.proto.payment.PaymentRequest;
import com.portfolio.proto.payment.PaymentResponse;
import com.portfolio.proto.payment.PaymentServiceGrpc;
import com.portfolio.proto.payment.RefundRequest;
import com.portfolio.proto.payment.RefundResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
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

    public PaymentResponse processPayment(String orderId, double amount, String currency, PaymentMethod method) {
        return stub.processPayment(PaymentRequest.newBuilder()
            .setOrderId(orderId)
            .setAmount(amount)
            .setCurrency(currency)
            .setMethod(method)
            .build());
    }

    public RefundResponse refundPayment(String paymentId, String reason) {
        return stub.refundPayment(RefundRequest.newBuilder()
            .setPaymentId(paymentId)
            .setReason(reason)
            .build());
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
