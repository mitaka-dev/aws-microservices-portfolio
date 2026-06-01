# ADR 004 — SNS + SQS over a Managed Message Broker

## Context

`order-service` needs to publish order lifecycle events (order created, confirmed, failed) for asynchronous processing. The processing step calls `catalog-service` via gRPC to decrement stock. Requirements:

- At-least-once delivery with acknowledgement
- Dead-letter queue for failed messages
- No broker infrastructure to operate
- Native IAM authentication (no username/password management)

Options evaluated: SNS + SQS, Amazon MQ (RabbitMQ or ActiveMQ), Amazon MSK (Kafka), EventBridge.

## Decision

Use **SNS** as the fan-out topic and **SQS** as the point-to-point processing queue, with a dead-letter queue for unprocessable messages.

```
order-service → SNS (orders-events) → SQS (orders-processing) → order-service consumer
                                                             ↘ SQS DLQ (orders-processing-dlq)
```

`order-service` acts as both the publisher (via `SnsTemplate`) and the consumer (via a manual `SqsMessagePoller` SmartLifecycle — `SqsAutoConfiguration` is excluded due to a Spring Boot 4 compatibility issue; see CLAUDE.md).

## Consequences

**Positive:**
- Fully managed: no broker to size, patch, or monitor. SNS and SQS are effectively infinite-scale serverless primitives.
- DLQ is a first-class concept: messages that fail processing N times (configured via `maxReceiveCount`) are automatically moved to the DLQ. A CloudWatch alarm fires when the DLQ depth exceeds zero.
- IAM-native: the ECS task role grants `sns:Publish` and `sqs:ReceiveMessage` permissions. No broker credentials to rotate.
- Fan-out is built in: adding a second consumer (e.g., an analytics service) requires subscribing a new SQS queue to the SNS topic — no changes to `order-service`.
- Cost: effectively free at portfolio scale (1M SQS requests/month free tier, 1M SNS publishes/month free tier).

**Negative:**
- SQS visibility timeout requires careful tuning: if processing takes longer than the timeout, the message becomes visible again and is reprocessed (at-least-once, not exactly-once).
- No message ordering without FIFO queues (which have lower throughput limits and higher cost).
- No complex routing patterns (topic exchanges, routing keys) — SNS filter policies cover simple cases but are not as expressive as AMQP routing.

## Alternatives Considered

**Amazon MQ (RabbitMQ):** Full AMQP support: topic exchanges, routing keys, per-message TTL, priority queues. Requires a managed broker instance starting at ~$0.10/hour ($2.40/day) — more expensive than the entire rest of the project. Justified when migrating an existing RabbitMQ workload or when complex routing is needed.

**Amazon MSK (Kafka):** Partitioned, ordered, replayable event log. Ideal for event sourcing, audit trails, and streaming analytics. Minimum cluster cost ~$0.21/hour per broker ($5/day for a 3-broker cluster). The operational model (consumer groups, offset management, partition rebalancing) is significantly more complex. Not justified for a single producer/consumer pair.

**EventBridge:** Better suited for SaaS integrations, cross-account event routing, and schema registry workflows. Higher per-event cost at scale and less flexible for point-to-point queue semantics. SNS/SQS is simpler for this internal async pattern.
