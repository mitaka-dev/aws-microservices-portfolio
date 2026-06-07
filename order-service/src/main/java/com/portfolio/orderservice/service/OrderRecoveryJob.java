package com.portfolio.orderservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.orderservice.grpc.CatalogGrpcClient;
import com.portfolio.orderservice.grpc.PaymentGrpcClient;
import com.portfolio.orderservice.model.OrderStatus;
import com.portfolio.orderservice.model.SagaCompensationStep;
import com.portfolio.orderservice.repository.OrderRepository;
import com.portfolio.orderservice.repository.SagaCompensationStepRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class OrderRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(OrderRecoveryJob.class);

    // Fire a CloudWatch metric at this many retries — compensation is struggling, investigate.
    private static final int ALERT_RETRY_THRESHOLD = 5;

    private final OrderRepository orderRepository;
    private final PaymentGrpcClient paymentGrpcClient;
    private final CatalogGrpcClient catalogGrpcClient;
    private final SagaCompensationStepRepository compensationStepRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public OrderRecoveryJob(OrderRepository orderRepository,
                            PaymentGrpcClient paymentGrpcClient,
                            CatalogGrpcClient catalogGrpcClient,
                            SagaCompensationStepRepository compensationStepRepository,
                            ObjectMapper objectMapper,
                            MeterRegistry meterRegistry) {
        this.orderRepository = orderRepository;
        this.paymentGrpcClient = paymentGrpcClient;
        this.catalogGrpcClient = catalogGrpcClient;
        this.compensationStepRepository = compensationStepRepository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    /** Retry stuck COMPENSATING orders — handles refund failures. */
    @Scheduled(fixedDelay = 300_000)
    public void recover() {
        Instant cutoff = Instant.now().minus(5, ChronoUnit.MINUTES);
        var stuckOrders = orderRepository.findByStatusAndUpdatedAtBefore(OrderStatus.COMPENSATING, cutoff);

        for (var order : stuckOrders) {
            try {
                var refundResponse = paymentGrpcClient.refundPayment(
                    order.getPaymentId(), "Recovery job retry");
                if (refundResponse.getSuccess()) {
                    order.setStatus(OrderStatus.FAILED);
                    orderRepository.save(order);
                    log.info("Recovery: refund succeeded for orderId={}", order.getId());
                } else {
                    log.error("Recovery: refund returned failure for orderId={}, paymentId={}",
                        order.getId(), order.getPaymentId());
                }
            } catch (Exception e) {
                log.error("Recovery: refund call failed for orderId={}, paymentId={} — will retry next cycle",
                    order.getId(), order.getPaymentId(), e);
            }
        }
    }

    /**
     * Retry failed stock increment compensation steps with exponential backoff.
     *
     * Backoff: min(2^retryCount, 3600) seconds between attempts.
     * Alert threshold (retryCount == 5): fires saga.compensation.struggling.total metric.
     * Dead-letter (retryCount >= maxRetries OR gRPC NOT_FOUND): fires saga.compensation.dead_letter.total.
     *
     * See docs/runbooks/stock-compensation-runbook.md for how to respond to alerts and dead-letters.
     */
    @Scheduled(fixedDelay = 300_000)
    public void recoverCompensationSteps() {
        List<SagaCompensationStep> steps = compensationStepRepository
            .findByStatusInOrderByCreatedAtAsc(List.of("PENDING", "RETRYING"));
        if (steps.isEmpty()) return;

        Instant now = Instant.now();

        for (SagaCompensationStep step : steps) {
            if (!isDue(step, now)) continue;

            compensationStepRepository.recordAttempt(step.getId(), now);

            try {
                executeStep(step);
                compensationStepRepository.markCompleted(step.getId());
                log.info("Compensation step completed: id={}, orderId={}", step.getId(), step.getOrderId());

            } catch (io.grpc.StatusRuntimeException e) {
                handleGrpcFailure(step, e, now);
            } catch (Exception e) {
                log.error("Unexpected error in compensation step id={}: {}", step.getId(), e.getMessage(), e);
            }
        }
    }

    private void handleGrpcFailure(SagaCompensationStep step, io.grpc.StatusRuntimeException e, Instant now) {
        int newRetryCount = step.getRetryCount() + 1;

        // Permanent failure: product deleted — retrying will never succeed.
        if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
            compensationStepRepository.markDeadLetter(step.getId(), now);
            meterRegistry.counter("saga.compensation.dead_letter.total", "reason", "product_not_found").increment();
            log.error("Compensation step DEAD_LETTER (product not found): id={}, orderId={} — see runbook",
                step.getId(), step.getOrderId());
            return;
        }

        // Transient failure — alert at threshold, dead-letter at max.
        if (newRetryCount >= step.getMaxRetries()) {
            compensationStepRepository.markDeadLetter(step.getId(), now);
            meterRegistry.counter("saga.compensation.dead_letter.total", "reason", "max_retries_exceeded").increment();
            log.error("Compensation step DEAD_LETTER ({} retries exhausted): id={}, orderId={} — see runbook",
                step.getMaxRetries(), step.getId(), step.getOrderId());
        } else {
            if (newRetryCount == ALERT_RETRY_THRESHOLD) {
                meterRegistry.counter("saga.compensation.struggling.total").increment();
                log.warn("Compensation step at alert threshold ({} retries): id={}, orderId={}",
                    ALERT_RETRY_THRESHOLD, step.getId(), step.getOrderId());
            }
            log.warn("Compensation step transient failure retry {}/{}: id={}, next in {}s",
                newRetryCount, step.getMaxRetries(), step.getId(), backoffSeconds(newRetryCount));
        }
    }

    private void executeStep(SagaCompensationStep step) throws Exception {
        if ("INCREMENT_STOCK".equals(step.getStepName())) {
            var payload = objectMapper.readValue(step.getPayload(), OrderService.StockCompensationPayload.class);
            catalogGrpcClient.incrementStock(payload.productId(), payload.quantity());
        } else {
            throw new IllegalArgumentException("Unknown compensation step: " + step.getStepName());
        }
    }

    private boolean isDue(SagaCompensationStep step, Instant now) {
        if (step.getLastAttemptedAt() == null) return true;
        return step.getLastAttemptedAt().plusSeconds(backoffSeconds(step.getRetryCount())).isBefore(now);
    }

    /** Exponential backoff capped at 1 hour: min(2^retryCount, 3600) seconds. */
    private long backoffSeconds(int retryCount) {
        return Math.min((long) Math.pow(2, retryCount), 3600L);
    }
}
