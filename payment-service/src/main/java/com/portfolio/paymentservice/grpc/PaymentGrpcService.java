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
import com.portfolio.proto.payment.RefundRequest;
import com.portfolio.proto.payment.RefundResponse;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class PaymentGrpcService extends PaymentServiceGrpc.PaymentServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(PaymentGrpcService.class);

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
        // Idempotency: if this orderId already has a SUCCESS record, return it without re-charging.
        // FAILED records are allowed to be retried (no charge was taken).
        Optional<PaymentRecord> existing = paymentRecordRepository.findByOrderId(request.getOrderId());
        if (existing.isPresent() && existing.get().getStatus() == PaymentStatus.SUCCESS) {
            log.info("Idempotent processPayment for orderId={} — returning existing SUCCESS record",
                request.getOrderId());
            responseObserver.onNext(toSuccessResponse(existing.get()));
            responseObserver.onCompleted();
            return;
        }

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

        responseObserver.onNext(PaymentResponse.newBuilder()
            .setPaymentId(saved.getId().toString())
            .setStatus(result.success()
                ? com.portfolio.proto.payment.PaymentStatus.SUCCESS
                : com.portfolio.proto.payment.PaymentStatus.FAILED)
            .setFailureReason(result.failureReason())
            .build());
        responseObserver.onCompleted();
    }

    @Override
    public void refundPayment(RefundRequest request, StreamObserver<RefundResponse> responseObserver) {
        UUID paymentId = UUID.fromString(request.getPaymentId());
        PaymentRecord record = paymentRecordRepository.findById(paymentId).orElse(null);

        if (record == null) {
            log.warn("Refund for unknown paymentId={}", paymentId);
            responseObserver.onNext(RefundResponse.newBuilder().setSuccess(false).build());
            responseObserver.onCompleted();
            return;
        }

        // Idempotency: if already refunded, return success without a second DB write.
        if (record.getStatus() == PaymentStatus.REFUNDED) {
            log.info("Idempotent refundPayment for paymentId={} — already REFUNDED", paymentId);
            responseObserver.onNext(RefundResponse.newBuilder().setSuccess(true).build());
            responseObserver.onCompleted();
            return;
        }

        paymentRecordRepository.updateStatus(paymentId, PaymentStatus.REFUNDED);
        log.info("Refunded paymentId={}", paymentId);
        responseObserver.onNext(RefundResponse.newBuilder().setSuccess(true).build());
        responseObserver.onCompleted();
    }

    private PaymentResponse toSuccessResponse(PaymentRecord record) {
        return PaymentResponse.newBuilder()
            .setPaymentId(record.getId().toString())
            .setStatus(com.portfolio.proto.payment.PaymentStatus.SUCCESS)
            .setFailureReason("")
            .build();
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
