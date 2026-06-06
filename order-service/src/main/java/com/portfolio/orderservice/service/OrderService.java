package com.portfolio.orderservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.orderservice.dto.CreateOrderRequest;
import com.portfolio.orderservice.dto.OrderResponse;
import com.portfolio.orderservice.grpc.CatalogGrpcClient;
import com.portfolio.orderservice.grpc.PaymentGrpcClient;
import com.portfolio.orderservice.messaging.OrderConfirmedEvent;
import com.portfolio.orderservice.model.Order;
import com.portfolio.orderservice.model.OrderItem;
import com.portfolio.orderservice.model.OrderStatus;
import com.portfolio.orderservice.model.OutboxEvent;
import com.portfolio.orderservice.repository.OrderRepository;
import com.portfolio.proto.payment.PaymentMethod;
import com.portfolio.proto.payment.PaymentResponse;
import com.portfolio.proto.payment.PaymentStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final PaymentGrpcClient paymentGrpcClient;
    private final CatalogGrpcClient catalogGrpcClient;
    private final OrderSagaHelper sagaHelper;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public OrderService(OrderRepository orderRepository,
                        PaymentGrpcClient paymentGrpcClient,
                        CatalogGrpcClient catalogGrpcClient,
                        OrderSagaHelper sagaHelper,
                        ObjectMapper objectMapper,
                        MeterRegistry meterRegistry) {
        this.orderRepository = orderRepository;
        this.paymentGrpcClient = paymentGrpcClient;
        this.catalogGrpcClient = catalogGrpcClient;
        this.sagaHelper = sagaHelper;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    // No @Transactional here — each orderRepository.save() commits independently
    // as a durable saga checkpoint via Spring Data's own @Transactional on save().
    public OrderResponse createOrder(CreateOrderRequest req) {
        List<OrderItem> items = req.items().stream().map(itemReq -> {
            OrderItem item = new OrderItem();
            item.setProductId(itemReq.productId());
            item.setQuantity(itemReq.quantity());
            item.setUnitPrice(itemReq.unitPrice());
            return item;
        }).toList();

        BigDecimal total = items.stream()
            .map(i -> i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        Order order = new Order();
        order.setUserId(req.userId());
        order.setTotalAmount(total);
        items.forEach(i -> i.setOrder(order));
        order.setItems(items);
        Order saved = orderRepository.save(order);  // checkpoint 1: PENDING

        PaymentMethod method = parsePaymentMethod(req.paymentMethod());
        PaymentResponse paymentResponse = paymentGrpcClient.processPayment(
            saved.getId().toString(), total.doubleValue(), "USD", method);

        if (paymentResponse.getStatus() == PaymentStatus.FAILED) {
            saved.setStatus(OrderStatus.FAILED);
            orderRepository.save(saved);
            log.warn("Payment failed for orderId={}: {}", saved.getId(), paymentResponse.getFailureReason());
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                "Payment failed: " + paymentResponse.getFailureReason());
        }

        saved.setStatus(OrderStatus.PAID);
        saved.setPaymentId(paymentResponse.getPaymentId());
        orderRepository.save(saved);  // checkpoint 2: PAID — money taken

        boolean stockFailed = false;
        for (var itemReq : req.items()) {
            try {
                catalogGrpcClient.decrementStock(itemReq.productId(), itemReq.quantity());
            } catch (Exception e) {
                log.error("Stock decrement failed for productId={}, orderId={}", itemReq.productId(), saved.getId(), e);
                stockFailed = true;
                break;
            }
        }

        if (stockFailed) {
            saved.setStatus(OrderStatus.COMPENSATING);
            orderRepository.save(saved);
            try {
                paymentGrpcClient.refundPayment(saved.getPaymentId(), "Stock decrement failed");
                log.info("Refund succeeded for orderId={}", saved.getId());
            } catch (Exception e) {
                log.error("Refund failed for orderId={}, paymentId={} — left in COMPENSATING for recovery job",
                    saved.getId(), saved.getPaymentId(), e);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Order processing failed");
            }
            saved.setStatus(OrderStatus.FAILED);
            orderRepository.save(saved);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Order processing failed");
        }

        // checkpoint 3: CONFIRMED + outbox row — atomic
        String payload = serializeEvent(new OrderConfirmedEvent(saved.getId().toString(), saved.getUserId(), total));
        OutboxEvent outboxEvent = new OutboxEvent("Order", saved.getId().toString(), "OrderConfirmed", payload);
        sagaHelper.confirmWithOutbox(saved, outboxEvent);

        meterRegistry.counter("orders.created.total").increment();
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

    private PaymentMethod parsePaymentMethod(String method) {
        return switch (method.toUpperCase()) {
            case "PAYPAL" -> PaymentMethod.PAYPAL;
            case "BANK_TRANSFER" -> PaymentMethod.BANK_TRANSFER;
            default -> PaymentMethod.CREDIT_CARD;
        };
    }

    private String serializeEvent(OrderConfirmedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize outbox event", e);
        }
    }
}
