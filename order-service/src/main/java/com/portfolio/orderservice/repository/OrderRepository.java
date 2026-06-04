package com.portfolio.orderservice.repository;

import com.portfolio.orderservice.model.Order;
import com.portfolio.orderservice.model.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    List<Order> findByStatusAndUpdatedAtBefore(OrderStatus status, Instant before);
}
