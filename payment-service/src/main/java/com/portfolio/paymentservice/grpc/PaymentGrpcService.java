package com.portfolio.paymentservice.grpc;

import com.portfolio.paymentservice.model.PaymentMethod;
import com.portfolio.paymentservice.model.PaymentRecord;
import com.portfolio.paymentservice.model.PaymentStatus;
import com.portfolio.paymentservice.repository.PaymentRecordRepository;
import com.portfolio.paymentservice.strategy.PaymentResult;
import com.portfolio.paymentservice.strategy.PaymentStrategy;
import com.portfolio.proto.payment.PaymentRequest;
import com.portfolio.proto.payment.PaymentResponse;
import com.portfolio.proto.payment.PaymentServiceGrpc;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

@Component
public class PaymentGrpcService extends PaymentServiceGrpc.PaymentServiceImplBase {

    private final Map<com.portfolio.proto.payment.PaymentMethod, PaymentStrategy> strategies;
    private final PaymentRecordRepository paymentRecordRepository;
    private final MeterRegistry meterRegistry;

    public PaymentGrpcService(
        Map<com.portfolio.proto.payment.PaymentMethod, PaymentStrategy> strategies,
        PaymentRecordRepository paymentRecordRepository,
        MeterRegistry meterRegistry
    ) {
        this.strategies = strategies;
        this.paymentRecordRepository = paymentRecordRepository;
        this.meterRegistry = meterRegistry;
    }

    @Override
    @Transactional
    public void processPayment(PaymentRequest request, StreamObserver<PaymentResponse> responseObserver) {
        String methodTag = request.getMethod().name().toLowerCase();
        meterRegistry.counter("payment.attempts.total", "method", methodTag).increment();

        PaymentStrategy strategy = strategies.get(request.getMethod());
        PaymentResult result = strategy.process(request);

        PaymentStatus status = result.success() ? PaymentStatus.SUCCESS : PaymentStatus.FAILED;
        PaymentMethod method = toModelMethod(request.getMethod());

        PaymentRecord record = new PaymentRecord(
            request.getOrderId(),
            BigDecimal.valueOf(request.getAmount()),
            request.getCurrency(),
            method,
            status,
            result.failureReason()
        );
        PaymentRecord saved = paymentRecordRepository.save(record);

        if (result.success()) {
            meterRegistry.counter("payment.success.total", "method", methodTag).increment();
        } else {
            meterRegistry.counter("payment.failure.total", "method", methodTag).increment();
        }

        PaymentResponse response = PaymentResponse.newBuilder()
            .setPaymentId(saved.getId().toString())
            .setStatus(result.success()
                ? com.portfolio.proto.payment.PaymentStatus.SUCCESS
                : com.portfolio.proto.payment.PaymentStatus.FAILED)
            .setFailureReason(result.failureReason())
            .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private PaymentMethod toModelMethod(com.portfolio.proto.payment.PaymentMethod protoMethod) {
        return switch (protoMethod) {
            case CREDIT_CARD -> PaymentMethod.CREDIT_CARD;
            case PAYPAL -> PaymentMethod.PAYPAL;
            case BANK_TRANSFER -> PaymentMethod.BANK_TRANSFER;
            default -> throw new IllegalArgumentException("Unknown payment method: " + protoMethod);
        };
    }
}
