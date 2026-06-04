package com.portfolio.orderservice.service;

import com.portfolio.orderservice.model.Order;
import com.portfolio.orderservice.model.OrderStatus;
import com.portfolio.orderservice.model.OutboxEvent;
import com.portfolio.orderservice.repository.OrderRepository;
import com.portfolio.orderservice.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles the final saga checkpoint: saves CONFIRMED status and the outbox event
 * atomically in one transaction. A separate bean is required because Spring's AOP
 * proxy cannot intercept @Transactional calls made from within the same class.
 */
@Service
public class OrderSagaHelper {

    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxEventRepository;

    public OrderSagaHelper(OrderRepository orderRepository,
                           OutboxEventRepository outboxEventRepository) {
        this.orderRepository = orderRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional
    public void confirmWithOutbox(Order order, OutboxEvent outboxEvent) {
        order.setStatus(OrderStatus.CONFIRMED);
        orderRepository.save(order);
        outboxEventRepository.save(outboxEvent);
    }
}
