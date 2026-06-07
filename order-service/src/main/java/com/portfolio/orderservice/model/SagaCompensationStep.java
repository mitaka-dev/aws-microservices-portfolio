package com.portfolio.orderservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "saga_compensation_steps")
public class SagaCompensationStep {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "step_name", nullable = false, length = 50)
    private String stepName;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 50;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_attempted_at")
    private Instant lastAttemptedAt;

    protected SagaCompensationStep() {}

    public SagaCompensationStep(UUID orderId, String stepName, String payload) {
        this.orderId = orderId;
        this.stepName = stepName;
        this.payload = payload;
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public String getStepName() { return stepName; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
    public int getMaxRetries() { return maxRetries; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastAttemptedAt() { return lastAttemptedAt; }
    public void setStatus(String status) { this.status = status; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public void setLastAttemptedAt(Instant lastAttemptedAt) { this.lastAttemptedAt = lastAttemptedAt; }
}
