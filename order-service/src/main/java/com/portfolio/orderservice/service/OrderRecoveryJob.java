package com.portfolio.orderservice.service;

import com.portfolio.orderservice.grpc.PaymentGrpcClient;
import com.portfolio.orderservice.model.OrderStatus;
import com.portfolio.orderservice.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class OrderRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(OrderRecoveryJob.class);

    private final OrderRepository orderRepository;
    private final PaymentGrpcClient paymentGrpcClient;

    public OrderRecoveryJob(OrderRepository orderRepository,
                            PaymentGrpcClient paymentGrpcClient) {
        this.orderRepository = orderRepository;
        this.paymentGrpcClient = paymentGrpcClient;
    }

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
                    log.error("Recovery: refund returned failure for orderId={}, paymentId={} — manual intervention required",
                        order.getId(), order.getPaymentId());
                }
            } catch (Exception e) {
                log.error("Recovery: refund call failed for orderId={}, paymentId={} — will retry next cycle",
                    order.getId(), order.getPaymentId(), e);
            }
        }
    }
}
