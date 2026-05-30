package com.portfolio.orderservice.service;

import com.portfolio.orderservice.dto.CreateOrderRequest;
import com.portfolio.orderservice.dto.OrderResponse;
import com.portfolio.orderservice.messaging.OrderCreatedEvent;
import com.portfolio.orderservice.messaging.OrderEventPublisher;
import com.portfolio.orderservice.messaging.OrderItemEvent;
import com.portfolio.orderservice.model.Order;
import com.portfolio.orderservice.model.OrderItem;
import com.portfolio.orderservice.repository.OrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher eventPublisher;

    public OrderService(OrderRepository orderRepository, OrderEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest req) {
        Order order = new Order();
        order.setUserId(req.userId());

        List<OrderItem> items = req.items().stream().map(itemReq -> {
            OrderItem item = new OrderItem();
            item.setProductId(itemReq.productId());
            item.setQuantity(itemReq.quantity());
            item.setUnitPrice(itemReq.unitPrice());
            item.setOrder(order);
            return item;
        }).toList();

        BigDecimal total = items.stream()
            .map(i -> i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        order.setTotalAmount(total);
        order.setItems(items);
        Order saved = orderRepository.save(order);

        var event = new OrderCreatedEvent(
            saved.getId().toString(),
            saved.getUserId(),
            items.stream().map(i -> new OrderItemEvent(i.getProductId(), i.getQuantity(), i.getUnitPrice())).toList()
        );
        eventPublisher.publishOrderCreated(event);

        return OrderResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID id) {
        return orderRepository.findById(id)
            .map(OrderResponse::from)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> listOrders() {
        return orderRepository.findAll().stream().map(OrderResponse::from).toList();
    }
}
