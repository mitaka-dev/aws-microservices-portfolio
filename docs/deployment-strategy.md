# Zero-Downtime Deployment Strategy

## Overview

This document describes the zero-downtime deployment strategy chosen for the portfolio microservices system, the reasoning behind the choice, and the specific challenges introduced by the system's stateful layers — along with the solutions required to make the strategy actually work in practice.

---

## Deployment Mechanism: Blue/Green

### How It Works

Blue/Green deployment maintains two complete sets of ECS tasks behind a single Application Load Balancer. The ALB has one production listener rule that points at a target group. At any moment, one target group is "live" (blue) and the other is idle (green). When a new version is deployed:

1. The new version's tasks start in the green target group.
2. CodeDeploy (or a manual ALB rule shift) runs health checks against the green group.
3. Once green is healthy, the listener rule is atomically switched: 100% of traffic moves from blue to green in a single operation, or gradually over a configured bake window.
4. The old blue tasks remain running for a configurable termination delay, allowing instant rollback.
5. After the delay, blue tasks are terminated.

On AWS this is implemented via ECS + CodeDeploy with a `BlueGreenDeployment` configuration, two target groups on the ALB, and a `taskdef.json` / `appspec.yaml` artifact produced by CI.

### Why Blue/Green Over the Alternatives

**Rolling** (the ECS default) replaces tasks one at a time. It requires zero extra infra and is easy to set up, but it means old and new task versions are simultaneously serving production traffic for the entire duration of the rollout. For a system with a relational database, SQS consumers, and a gRPC contract between services, this overlap window is the source of almost every production incident involving deployments. Rolling is fine when every change is purely additive, but it provides no safety net when it is not.

**Canary** (weighted target groups or a service mesh) sends a small percentage of traffic to the new version before fully cutting over. It catches production-only bugs with a low blast radius, but it requires either ALB weighted routing (coarse-grained, per-request not per-user) or a full service mesh like AWS App Mesh. The added complexity is difficult to justify for a four-service portfolio where request volume is controlled.

**Blue/Green** gives the best trade-off: the new version is fully validated under zero real traffic before the cutover, the cutover itself is near-instantaneous (no overlap window), and rollback is a single API call as long as the old tasks are still running.

### Pros

- **No overlap window.** Old and new versions never serve traffic at the same time. This dramatically simplifies the requirements on schema migrations, message formats, and gRPC contracts — you still need backward compatibility during the deployment *process*, but not during sustained parallel operation.
- **Instant rollback.** Flipping the ALB listener rule back to blue takes seconds. The old tasks are still running and healthy, so rollback has no warm-up cost.
- **Pre-traffic validation hooks.** CodeDeploy supports `BeforeAllowTraffic` and `AfterAllowTraffic` Lambda hooks. You can run a smoke test or a canary check against the green target group before any production traffic reaches it, and abort the deployment if the check fails.
- **Well-understood on AWS.** ECS + CodeDeploy blue/green is a first-class AWS pattern with native CloudFormation/OpenTofu support, documented IAM permissions, and built-in CloudWatch metrics.

### Cons and Trade-offs

- **Double task count during deployment.** For the duration of the deployment (from green task startup to blue task termination), both sets of tasks are running simultaneously. For a four-service system with Fargate tasks, this means temporary cost increase during deployments. For a portfolio this is a minor concern — deployments are infrequent and short.
- **Longer deployment pipeline.** Rolling updates are fast because they replace tasks in-place. Blue/green must wait for all green tasks to pass health checks before the cutover, which on this system can take 2–3 minutes given Fargate cold-start and Spring Boot startup time (~107 seconds on 256 CPU / 512 MB). The total wall-clock time from `docker push` to live traffic is longer.
- **CodeDeploy wiring required.** You need an `appspec.yaml`, a `taskdef.json` artifact, a CodeDeploy application and deployment group per service, and IAM permissions for CodeDeploy to manipulate ECS and the ALB. This is non-trivial IaC to write correctly the first time.
- **Rollback window is finite.** CodeDeploy's `terminationWaitTimeInMinutes` defaults to 5 minutes. After that, the blue task set is gone. If you discover a problem 10 minutes after deployment, rollback means a full forward deployment of the previous image — not the instant flip.
- **Database migrations are not rolled back automatically.** If you roll back the application, the schema migration that ran at green startup remains applied. The rollback is only safe if the migration was backward-compatible with the previous application version (the one running on blue). This is the most important constraint in the entire strategy and is covered in detail below.

---

## Stateful Layer: RDS PostgreSQL with Flyway

### The Problem

Flyway runs migrations at application startup. In a blue/green deployment, the green tasks start, the first green task to acquire the Flyway lock runs all pending migrations, and then the cutover happens. At that point the blue tasks are terminated. This sounds orderly, but the schema is now in the new state while the old application code is still potentially processing in-flight requests (during the termination grace period) or during a rollback.

The acute failure mode: you rename a column or drop a nullable column in the migration. After green starts, the database has the new schema. If you roll back and blue restarts, it tries to write to a column that no longer exists. Or, in a rolling scenario (which blue/green avoids, but is worth understanding for contrast), old tasks are writing to `user_name` while new tasks expect `username` — both reading the same table.

The secondary failure mode is more subtle: adding a `NOT NULL` column without a default in a single migration. The migration runs, existing rows are unaffected by the `NOT NULL` (PostgreSQL applies the constraint prospectively), but old code that does not include the new column in its `INSERT` statements will fail with a constraint violation the moment a new row is written.

### The Solution: Expand-Contract (Three-Phase Migration)

Non-additive schema changes must be split across at minimum two separate deployments:

**Phase 1 — Expand (deploy new code first, migration adds the new shape alongside the old):**
- Add the new column as nullable, or add the new table.
- New application code writes to *both* old and new columns/tables.
- Old application code continues to read and write the old column — it does not know the new one exists.
- Migration in this deployment: `ALTER TABLE users ADD COLUMN username VARCHAR(255);`

**Phase 2 — Backfill (data migration, usually a separate migration file):**
- Populate the new column from the old one for all existing rows.
- This can be a Flyway migration or a one-off script, depending on table size. For large tables, batched updates outside Flyway are safer to avoid long-running transactions.

**Phase 3 — Contract (remove the old shape, new code uses only the new column):**
- Drop the old column.
- New application code reads and writes only the new column.
- Migration: `ALTER TABLE users DROP COLUMN user_name;`

**What is additive (safe in a single deployment):**
- Adding a nullable column
- Adding a new table
- Adding an index (use `CREATE INDEX CONCURRENTLY` to avoid table lock on PostgreSQL)
- Widening a varchar column

**What is not additive (requires expand-contract):**
- Renaming a column or table
- Dropping a column or table
- Adding a `NOT NULL` column without a default
- Changing a column type in an incompatible direction

**Specific to this system:** `user-service` and `order-service` use PostgreSQL with Flyway. Migrations live in `src/main/resources/db/migration/`. Every migration file must be reviewed against this checklist before it is merged to main. A useful convention is to add a comment at the top of any migration file that requires a follow-up contract phase: `-- CONTRACT REQUIRED: drop user_name after next deployment`.

---

## Stateful Layer: SQS Messages (order-service)

### The Problem

`order-service` publishes events to the SNS `orders-events` topic, which fans out to the SQS `orders-processing` queue. The consumer is the `SqsMessagePoller` inside `order-service` itself (it both produces and consumes). In a blue/green deployment the overlap is short — the cutover is atomic — but there is always a window where messages produced by one version of the code are consumed by a different version.

The concrete scenarios:

1. Green tasks produce a message with a new field (`"discountCode": "SUMMER10"`). The cutover fails, you roll back. Blue tasks try to deserialize the message. If the old `OrderEvent` class has no `discountCode` field and Jackson is configured to fail on unknown properties, deserialization throws, the message goes to the dead-letter queue, and the order is stuck.

2. You remove a field from the event. Green produces messages without it. A rollback means blue tasks consume messages that are missing a field they expect as non-null.

3. You change the type of a field (`String` discount → `BigDecimal` discountAmount). Any message in the queue from green code is unparseable by blue code and vice versa.

### The Solution: Additive-Only Changes and a Versioned Envelope

**Additive-only rule:** Never remove or rename a field in an SQS message class. Only add new fields, and always annotate them with `@JsonIgnoreProperties(ignoreUnknown = true)` at the class level. This means old consumers silently ignore fields they do not understand, and new consumers handle absent fields gracefully (use `Optional` or a sensible default).

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderEvent(
    String orderId,
    String userId,
    String status,
    // Added in v2 — old consumers will ignore this field
    String discountCode
) {}
```

**Versioned envelope (for breaking changes):** When an additive change is not possible, wrap the payload in an envelope that carries a `schemaVersion` field. The consumer checks the version and routes to the appropriate deserialization path. Old messages in the queue (produced by blue) will have `schemaVersion: 1`; new messages (produced by green) will have `schemaVersion: 2`. Both consumers must handle both versions during any transition period.

**DLQ monitoring:** The `orders-processing-dlq` queue already has a CloudWatch alarm. A spike in DLQ depth immediately after a deployment is the signal that message deserialization broke. The existing alarm means this failure mode is observable without manual intervention.

**Specific to this system:** The `SqsMessagePoller` uses `ObjectMapper` for deserialization. Ensure `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES` is set to `false` globally (in the `ObjectMapper` bean configuration), so the additive-only rule is enforced mechanically rather than relying on every developer remembering the annotation.

---

## Stateful Layer: ElastiCache Redis (catalog-service)

### The Problem

`catalog-service` uses Redis for caching catalog item reads (the DynamoDB query result cache). Redis is a shared external store — it persists across deployments. When a new version of the application starts, it may write cache entries in a different format than the previous version. When blue tasks read those entries (during a rollback) or when green tasks read entries that blue wrote (after cutover), deserialization can fail.

The most common failure mode: you add a field to the `CatalogItem` class that is cached as a serialized Java object (e.g., via Jackson to JSON in Redis). After deployment, the new field is absent from all existing cache entries. If the new code treats the field as non-null or non-optional and does not handle a cache miss gracefully, every cache read for a pre-existing entry throws or returns a corrupt object.

A second failure mode: you change the Redis key format (e.g., from `catalog:item:{id}` to `catalog:v2:item:{id}` to include a region). Old entries under the old key format will never be read and will accumulate until TTL expires, consuming memory. New code looking for the new key will always miss and fall through to DynamoDB — which is safe but defeats the cache.

### The Solution: Key Versioning and Defensive Deserialization

**Key version prefix:** Embed a schema version in every Redis key. If you change the cached object's structure in a non-backward-compatible way, increment the version prefix. Old keys under the previous prefix expire naturally. New code only reads and writes keys under the new prefix.

```
# v1 keys (written by blue)
catalog:v1:item:abc123

# v2 keys (written by green, after a breaking cache format change)
catalog:v2:item:abc123
```

The version can be a constant in the application config, making it easy to bump in code review without touching every cache-access call site.

**Defensive deserialization:** The cache should always be treated as a best-effort optimization, never as a source of truth. Every cache read must have a fallback to the DynamoDB read path. A deserialization failure should log a warning, evict the bad key (`redisTemplate.delete(key)`), and return the result from DynamoDB — not throw and bubble up a 500.

**TTL discipline:** Short TTLs (60–300 seconds for a portfolio system) reduce the blast radius of a format mismatch. A bad cache entry expires within minutes without any manual intervention.

**Specific to this system:** The Redis cluster uses `cache.t4g.micro` and the data set is small (catalog items). The risk of a cache format issue causing sustained DynamoDB overload is low because the table is on-demand billing. The practical risk is a bad cache read causing an exception and degrading catalog reads — which the defensive fallback pattern fully mitigates.

---

## Stateful Layer: gRPC Contract (order-service → catalog-service)

### The Problem

`order-service` calls `catalog-service` over gRPC to decrement (and increment) stock, and calls `payment-service` over gRPC to process and refund payments. Contracts are defined in `.proto` files in `proto-catalog` and `proto-payment` respectively. If a proto definition changes in a non-backward-compatible way, the caller and server can become incompatible during a deployment.

In a blue/green deployment with a coordinated multi-service rollout this is manageable, but if `catalog-service` is deployed before `order-service` (or vice versa), there is a window where the caller and the server are on different proto versions.

The concrete failure modes:

1. A field is removed from `DecrementStockRequest`. Old `order-service` (blue) sends the field; new `catalog-service` (green) ignores it. This is safe — protobuf handles unknown fields gracefully by default.

2. A field number is reused for a different type. `order-service` sends field 3 as a `string` (the old meaning). `catalog-service` expects field 3 as an `int32` (the new meaning). Wire decode fails or silently produces garbage. **This is the dangerous case.**

3. A required RPC is renamed or removed. `order-service` calls `DecrementStock`; the new `catalog-service` no longer has that method. gRPC returns `UNIMPLEMENTED`. Order processing halts.

### The Solution: Protobuf Compatibility Rules and Additive-Only Changes

**Protobuf's backward compatibility contract:**
- **Safe:** Add a new field with a new field number. Both old and new parsers handle this — old parsers ignore unknown fields, new parsers use the default value for absent fields.
- **Safe:** Add a new RPC method. Old callers never call it; new callers can use it.
- **Unsafe:** Remove a field number from a message (the number is reserved for past use — old senders may still send it).
- **Unsafe:** Reuse a field number for a different type.
- **Unsafe:** Remove or rename an RPC method that existing callers depend on.
- **Unsafe:** Change a field's type incompatibly (e.g., `string` → `int32`).

The rule in practice: **never remove a field number or reuse it**. Instead, mark the field as `reserved` in the `.proto` file:

```protobuf
message DecrementStockRequest {
  reserved 3;                      // was: string legacyField — removed in v2
  string catalog_item_id = 1;
  int32 quantity = 2;
  string order_id = 4;             // new field added in v2, safe
}
```

**Deployment ordering for breaking changes:** If a breaking proto change is unavoidable, the deploy sequence must be: deploy new `catalog-service` first (it can handle both old and new request formats), then deploy new `order-service`. Never the reverse. Document this in the CI pipeline for any deployment that involves a proto change.

**Specific to this system:** `proto-catalog` and `proto-payment` are the sources of truth for their respective contracts. Any PR that modifies a `.proto` file should be reviewed with the compatibility rules above as an explicit checklist item. `DecrementStock` and `ProcessPayment` are both in the critical path of order processing — a compatibility failure here causes orders to be marked `FAILED`. The DLQ depth alarm will fire, making the failure observable immediately after deployment.

---

## Summary

| Layer | Risk | Solution |
|---|---|---|
| ECS tasks | Old/new version overlap, no rollback | Blue/Green via CodeDeploy — atomic cutover, instant rollback |
| RDS / Flyway | Non-additive migration breaks old or new app | Expand-contract pattern — multi-phase migrations only |
| SQS messages | Old consumer can't read new message format | Additive-only fields, `ignoreUnknown = true`, versioned envelope for breaking changes |
| Redis cache | Cached objects in wrong format after deployment | Key version prefix, defensive deserialization fallback to DynamoDB |
| gRPC proto | Field number reuse or RPC removal breaks wire format | Never remove/reuse field numbers, mark as `reserved`, additive-only RPCs |
