---
name: resilience4j-patterns
description: Resilience4j circuit breakers, retries, bulkheads, and time limiters for this platform. Use when adding @CircuitBreaker, @Retry, @Bulkhead, @TimeLimiter, or editing resilience4j.* in application.yml.
allowed-tools: Read, Edit, Write, Bash(./mvnw *)
---

# Resilience4j Patterns

Resilience4j (latest via BOM) with Spring Boot 4 integration. Applied on all outbound calls: gRPC to catalog-service, SQS publishes, internal REST.

## Default Config (per service application.yml)

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 20
        minimum-number-of-calls: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 60s
        permitted-number-of-calls-in-half-open-state: 5
        record-exceptions:
          - java.io.IOException
          - java.util.concurrent.TimeoutException
        ignore-exceptions:
          - com.portfolio.{service}.exception.ValidationException
  retry:
    configs:
      default:
        max-attempts: 3
        wait-duration: 200ms
        exponential-backoff-multiplier: 2
        retry-exceptions:
          - java.io.IOException
          - java.util.concurrent.TimeoutException
        ignore-exceptions:
          - com.portfolio.{service}.exception.ClientErrorException
  timelimiter:
    configs:
      default:
        timeout-duration: 5s
        cancel-running-future: true
  bulkhead:
    configs:
      default:
        max-concurrent-calls: 25
        max-wait-duration: 100ms
```

## Per-Call-Type Recommended Settings

| Call | Timeout | Retries | CB threshold |
|---|---|---|---|
| gRPC internal (catalog-service) | 200ms | 2, 50ms backoff | 50% / 20 |
| REST internal | 500ms | 1 | 50% / 20 |
| SQS publish | 3s | 2, 200ms × 2 | 50% / 20 |
| DynamoDB | 1s | SDK default | n/a |

## Naming Convention

For Grafana dashboards — be consistent:
- Circuit breakers & retries: `{remote-target}-{operation}` — e.g. `catalog-grpc-get-item`, `sqs-order-events`
- Bulkheads: `{operation-class}` — e.g. `order-create`, `file-upload`
- Rate limiters: `{endpoint-or-feature}` — e.g. `login`, `register`

## Canonical Annotation Usage

```java
@CircuitBreaker(name = "catalog-grpc-get-item", fallbackMethod = "getItemFallback")
@Retry(name = "catalog-grpc-get-item")
@Bulkhead(name = "order-create", type = Bulkhead.Type.SEMAPHORE)
@TimeLimiter(name = "catalog-grpc-get-item")
public CompletableFuture<CatalogItemDto> getCatalogItem(String itemId) {
    return CompletableFuture.supplyAsync(() -> catalogGrpcClient.getItem(itemId));
}

public CompletableFuture<CatalogItemDto> getItemFallback(String itemId, Throwable t) {
    log.error("Catalog gRPC fallback for item {}", itemId, t);
    throw new CatalogServiceUnavailableException(itemId, t);
}
```

Annotation order matters: `@CircuitBreaker` outermost, `@TimeLimiter` innermost when stacked.

## Service-Level yml Override

```yaml
# order-service/src/main/resources/application.yml
resilience4j:
  circuitbreaker:
    instances:
      catalog-grpc-get-item:
        base-config: default
        failure-rate-threshold: 50
  retry:
    instances:
      catalog-grpc-get-item:
        base-config: default
        max-attempts: 2
        wait-duration: 50ms
        exponential-backoff-multiplier: 2
  bulkhead:
    instances:
      order-create:
        max-concurrent-calls: 20
```

## Key Rules

**Fallback methods:** same return type as the primary method, last parameter must be `Throwable`. For unavailable services: throw a wrapped domain exception. For non-critical reads: return a cached or degraded response.

**Bulkhead type:** Use `SEMAPHORE` (default). With Java 25 virtual threads, `THREADPOOL` defeats the model — avoid it.

**TimeLimiter scope:** Only works on `CompletableFuture<T>`. For sync calls, set the HTTP/gRPC client timeout directly + catch `TimeoutException` in the retry config. Do not put `@TimeLimiter` on sync methods.

**Retry safety:** Only retry idempotent operations. Safe to retry: GET, SDK reads, idempotent PUTs. Do NOT retry a non-idempotent POST without an idempotency key — you will create duplicate records.

## Metrics (auto-registered via Micrometer → /actuator/prometheus)

- `resilience4j.circuitbreaker.state{name=catalog-grpc-get-item}` — CLOSED/OPEN/HALF_OPEN
- `resilience4j.circuitbreaker.calls{name=catalog-grpc-get-item,kind=failed}`
- `resilience4j.retry.calls{name=catalog-grpc-get-item,kind=successful_without_retry}`
- `resilience4j.bulkhead.available.concurrent.calls{name=order-create}`

## CB State Change Logging

```java
@Bean
public RegistryEventConsumer<CircuitBreaker> cbStateLogger() {
    return new RegistryEventConsumer<>() {
        public void onEntryAddedEvent(EntryAddedEvent<CircuitBreaker> ev) {
            ev.getAddedEntry().getEventPublisher().onStateTransition(e ->
                log.warn("CB {} {} → {}",
                    e.getCircuitBreakerName(),
                    e.getStateTransition().getFromState(),
                    e.getStateTransition().getToState()));
        }
    };
}
```

## Anti-Patterns — Flag Immediately

| Anti-pattern | Fix |
|---|---|
| Annotation `name` has no matching `instances:` in yml | Add the yml config block |
| `retry-exceptions: java.lang.Throwable` | List specific retryable exceptions only |
| Retrying a non-idempotent POST without idempotency key | Use outbox or add idempotency check |
| `@CircuitBreaker` without `fallbackMethod` | Hard failure on circuit open — always add fallback |
| Fallback method with wrong return type | Must exactly match the primary method's return type |
| `Bulkhead.Type.THREADPOOL` with virtual threads | Use `SEMAPHORE` instead |
| `@TimeLimiter` on a sync method | Only works on `CompletableFuture<T>` |
| Hardcoded thresholds inside annotations | Move to `application.yml` instances block |
| Bulkhead `max-concurrent-calls` > connection pool size | Size bulkhead ≤ underlying pool |
