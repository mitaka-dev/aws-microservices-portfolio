---
name: outbox-pattern
description: Reliable event publishing via the transactional outbox pattern. Use when implementing SQS event publishing, async workflows, or any @Transactional method that also sends a message.
allowed-tools: Read, Edit, Write
---

# Outbox Pattern

## Why It Exists

Writing to a database and sending an SQS message in two separate operations causes inconsistency:
the DB write might succeed but the SQS send fail (or the JVM crash between the two), leaving the
system in an inconsistent state. The outbox pattern solves this by writing both atomically inside
a single DB transaction. A separate publisher then delivers reliably.

**Current approach in this project**: direct SQS publish inside `@Transactional` methods (no
outbox table yet). This is the existing pattern across services.
The full outbox table + polling publisher is a future phase. Both patterns are documented here.

---

## Existing Pattern (current codebase)

Used in services that publish SQS events:

```java
@Transactional
public OrderDto create(String userId, CreateOrderRequest request) {
    Order order = buildOrder(userId, request);
    orderRepository.save(order);                          // DB write
    sqsTemplate.send(to -> to                             // SQS publish (best-effort)
        .queue(orderEventsQueueUrl)
        .payload(new OrderCreatedEvent(order.getId(), userId)));
    return toDto(order);
}
```

**Risk**: if the JVM crashes after `save()` but before `sqsTemplate.send()`, the event is lost.
Acceptable for the current development phase; address with outbox table in production hardening.

---

## Full Outbox Pattern (production target)

### Step 1 — Outbox table migration

```sql
CREATE TABLE outbox (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(50)  NOT NULL,   -- e.g. 'ORDER'
    aggregate_id    VARCHAR(100) NOT NULL,   -- e.g. order UUID
    event_type      VARCHAR(100) NOT NULL,   -- e.g. 'ORDER_CREATED'
    partition_key   VARCHAR(100) NOT NULL,   -- used as SQS MessageGroupId for FIFO queues
    payload         JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
```

### Step 2 — Write to outbox inside the same transaction

```java
@Transactional
public OrderDto create(String userId, CreateOrderRequest request) {
    Order order = buildOrder(userId, request);
    orderRepository.save(order);

    // Write event to outbox in the SAME transaction — atomic with the state change
    outboxRepository.save(OutboxEntry.builder()
        .aggregateType("ORDER")
        .aggregateId(order.getId().toString())
        .eventType("ORDER_CREATED")
        .partitionKey(order.getId().toString())
        .payload(objectMapper.writeValueAsString(new OrderCreatedEvent(order.getId(), userId)))
        .build());

    return toDto(order);
}
```

### Step 3 — Outbox publisher (polling)

A `@Scheduled` publisher polls `WHERE published_at IS NULL`, sends to SQS, marks published:

```java
@Scheduled(fixedDelay = 1000)
@Transactional
public void publishPendingEvents() {
    List<OutboxEntry> pending = outboxRepository.findUnpublished(Pageable.ofSize(100));
    for (OutboxEntry entry : pending) {
        sqsTemplate.send(to -> to
            .queue(queueUrlFor(entry.getEventType()))
            .payload(entry.getPayload()));
        entry.setPublishedAt(Instant.now());
    }
    outboxRepository.saveAll(pending);
}
```

For concurrent publishers (multiple tasks), use `SELECT ... FOR UPDATE SKIP LOCKED` in the
`findUnpublished` query — see `/postgresql-flyway-migrations` locking patterns.

---

## Async Flow in This Project

```
POST /api/v1/orders
  → order-service: saves Order, publishes OrderCreatedEvent to order-events SQS queue
      ↓
  file-service: consumes OrderCreatedEvent → generates invoice PDF, uploads to S3
  order-service: (future) consumes file-ready event → updates Order with document URL
```

```
POST /api/v1/files
  → file-service: saves File metadata, uploads to S3, publishes FileUploadedEvent to file-events SQS queue
```

---

## Anti-Patterns

| Anti-pattern | Fix |
|---|---|
| `sqsTemplate.send()` BEFORE `repository.save()` | Always save to DB first |
| `sqsTemplate.send()` outside a `@Transactional` method | Move inside transaction boundary |
| SQS queue URL hardcoded as a string literal | Read from `${aws.sqs.queue-url}` property |
| No error handling on `sqsTemplate.send()` failure | Log and alert; in production use outbox for guaranteed delivery |
| Outbox poller without `FOR UPDATE SKIP LOCKED` on multi-task deployments | Multiple tasks will re-process the same rows |
