# ADR 008 — Pagination Strategy and HikariCP Connection Pool Sizing

## Context

Three list endpoints needed pagination: `GET /users`, `GET /orders`, and `GET /catalog`. Each maps to
a different backing store — PostgreSQL (users, orders) and DynamoDB (catalog). Unbounded list responses
were a regression risk under load: a single `findAll()` call at 1M rows exhausts heap and causes
cascading OOM failures.

Separately, the three PostgreSQL-backed services (user-service, order-service, payment-service) each
run inside a 256 vCPU (0.25 core) Fargate task. HikariCP defaults to `maximumPoolSize=10`, which was
set without regard to either the Fargate compute budget or the RDS instance's maximum connection limit.

---

## Decision 1 — Cursor pagination for DynamoDB, offset pagination for PostgreSQL

### DynamoDB (`GET /catalog`)

Use **DynamoDB cursor pagination** via `ExclusiveStartKey`.

DynamoDB has no `OFFSET` concept. The SDK's `QueryEnhancedRequest` accepts an `exclusiveStartKey`
(the key of the last item returned) and returns a `lastEvaluatedKey` alongside the page results.
This is the only correct pagination model for DynamoDB.

Implementation:
- `lastEvaluatedKey` (a `Map<String, AttributeValue>`) is serialised to JSON, then base64 URL-encoded
  into a `nextCursor` string in the response.
- The client sends `?cursor=<value>` on the next request; the server base64-decodes and deserialises
  back to `Map<String, AttributeValue>` to pass as `exclusiveStartKey`.
- Since all key attributes in this table are String type (`pk`, `sk`, `gsi1pk`, `gsi1sk`), the
  serialisation simplifies to `Map<String, String>` (no custom AttributeValue codec needed).
- A null `nextCursor` in the response signals the last page.

### PostgreSQL (`GET /users`, `GET /orders`)

Use **Spring Data offset pagination** via `Pageable` → `Page<T>`.

Spring Data JPA generates `LIMIT ? OFFSET ?` from a `Pageable` argument automatically. The response
wraps the `Page<T>` into a `PagedResponse<T>{ items, page, size, totalElements }` record.

**Why not cursor/keyset pagination for PostgreSQL too?**
- Keyset pagination (WHERE id > last_id) is more efficient than OFFSET at large page depths
  (OFFSET scans and discards rows; keyset does not). At 1M users this matters.
- However, keyset pagination requires a stable sort key known to the client and cannot easily support
  arbitrary sort orders. Spring Data's `Pageable` abstraction works with any sort expression; keyset
  requires bespoke query construction per sort column.
- At current scale (≤ 1M rows, standard sort orders), the OFFSET overhead is acceptable. If query
  plans show `idx_users_email` or `idx_orders_user_id` scans becoming slow, migrate to keyset at that
  point — not prematurely.

---

## Decision 2 — HikariCP pool sized at 5 per task

### Formula

The PostgreSQL performance literature (PgBouncer team, "HikariCP Sizing Notes") consistently
recommends:

```
max_connections_per_pool = (effective_cores * 2) + 1
```

A Fargate 256 CPU unit task has 0.25 vCPU. In practice, virtual threads (Project Loom, enabled on
all services) make I/O-bound threads cheap, but the bottleneck shifts to the database — not the
JVM thread count. The formula therefore applies to the number of database connections, not JVM threads.

```
effective_cores = 0.25 vCPU ≈ 1 (rounded up for burst capacity)
pool_size = (1 * 2) + 1 = 3
```

Rounded up to **5** to provide headroom for burst I/O during Fargate CPU credit accumulation periods.

### RDS constraint check

`db.t4g.micro` default `max_connections` ≈ 87.

At maximum scale:
- 3 PostgreSQL services (user, order, payment) × 5 pool per task × up to 4 tasks each = 60 connections maximum.
- 87 − 60 = 27 connections reserved for `psql` admin access and RDS monitoring agent.

This sizing is safe. Increasing `maximumPoolSize` beyond 5 without also scaling the RDS instance
causes connection exhaustion under autoscale spikes.

### Other pool settings

| Setting | Value | Rationale |
|---|---|---|
| `minimumIdle` | 2 | Keep 2 connections warm; avoid cold connection setup on burst traffic |
| `connectionTimeout` | 3 000 ms | Fail fast — shorter than ALB/API GW idle timeout so the task errors rather than hangs |
| `maxLifetime` | 1 800 000 ms | 30 min — below RDS `wait_timeout` (8h default) to prevent stale connections |
| `keepaliveTime` | 60 000 ms | Ping idle connections every 60 s to detect DB-side closures before a request hits a dead connection |
| `validationTimeout` | 2 000 ms | Fast validation query; if this times out the connection is unusable |

---

## Consequences

**Positive:**
- Cursor pagination for DynamoDB is the only correct model — no silent data drift or missing pages.
- Offset pagination for PostgreSQL requires zero bespoke query code at current scale.
- HikariCP sizing prevents connection exhaustion on the RDS instance during autoscale events.
- `maxLifetime < RDS wait_timeout` eliminates "connection closed by server" errors under low traffic.

**Negative:**
- Cursor tokens are opaque to the client — no random page access ("jump to page 5") for the catalog endpoint.
- Offset pagination degrades at deep pages (≥ 100k rows); monitor `pg_stat_statements` and migrate
  to keyset if `GET /users` or `GET /orders` slow down at high offset values.
- Pool size of 5 means at most 5 concurrent DB operations per task — adequate for virtual threads at
  current Fargate sizing, but requires task scale-out (not pool expansion) when concurrency increases.
