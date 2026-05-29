---
name: postgresql-flyway-migrations
description: >
  PostgreSQL schema migrations with Flyway for RDS PostgreSQL (t4g.micro) services in this
  portfolio. Covers migration file naming, the backwards-compatible change sequences that prevent
  outages, HikariCP connection pool tuning for RDS, locking patterns (FOR UPDATE / SKIP LOCKED /
  advisory locks), indexing rules, and Testcontainers-based integration test setup.

  ALWAYS load this skill when: writing or editing any Flyway migration SQL file
  (V*__*.sql), editing JPA entities or Spring Data repositories, configuring HikariCP or RDS
  datasource settings, or when the user mentions Flyway, schema migration, ALTER TABLE, column
  rename, DROP COLUMN, ddl-auto, HikariCP, connection pool, JDBC URL, or "migrate".
allowed-tools: Read, Edit, Write
---

# PostgreSQL + Flyway Migrations

> Stack: PostgreSQL 16 (RDS t4g.micro) · Flyway (via `spring-boot-flyway` module) · HikariCP · Spring Data JPA

## Migration File Naming

Format: `V{n}__{snake_case_description}.sql` — **exactly two underscores**, no gaps in `n`, no reuse.

```
V1__create_users.sql
V2__add_outbox_table.sql
V3__add_email_index.sql
V12__add_user_locale_column.sql
```

Migrations are **immutable** once merged. Editing a committed migration file causes Flyway to fail
validation on every task startup — which is worse than the original mistake. Never edit; always add
a new version.

## Flyway Config (locked in)

```yaml
spring:
  flyway:
    baseline-on-migrate: false   # require explicit baseline — never auto-create
    validate-on-migrate: true    # checksum check on startup
    out-of-order: false          # strict ascending order
    locations: classpath:db/migration
```

**Spring Boot 4 setup**: add the `spring-boot-flyway` module to the service POM (CLAUDE.md workaround #1). No manual `@Configuration` bean needed — the module handles autoconfiguration.

## Flyway on ECS Fargate Startup

Flyway runs inside the Spring Boot app on startup (not a separate init container). With multiple tasks
scaling in simultaneously, all tasks attempt migrations. Flyway handles this safely via a distributed
lock in the `flyway_schema_history` table — the first task acquires the lock, runs migrations, and
releases it; subsequent tasks see the schema is up-to-date.

**Prerequisite**: the RDS security group must allow inbound port 5432 from the ECS task security group
before any task can start. Wire this in Phase 3 before deploying services.

## Backwards-Compatible Change Patterns

These sequences prevent outages when a schema change and a code rollout happen simultaneously.
The rule: **the database must be readable by both the old and new code version** during any
rolling deployment window.

### Adding a column

```sql
-- Migration N (deploy this first, code unchanged):
ALTER TABLE users ADD COLUMN display_name TEXT NULL;

-- Then update code to write to the new column.
-- Migration N+1 (after all tasks run new code):
ALTER TABLE users ALTER COLUMN display_name SET NOT NULL;
```

### Removing a column

```sql
-- Step 1 (code change): stop reading and writing the column in application code.
-- Step 2 (migration, after full rollout):
ALTER TABLE users DROP COLUMN legacy_field;
```

Never drop first; always stop using it first.

### Renaming a column

This takes three migrations across three deploys:

```sql
-- Migration N: add new column alongside old
ALTER TABLE orders ADD COLUMN customer_ref TEXT NULL;

-- Code change: dual-write to both columns, read from new one.

-- Migration N+1: backfill rows that arrived before the dual-write
UPDATE orders SET customer_ref = old_ref WHERE customer_ref IS NULL;

-- Code change: stop writing to old column.

-- Migration N+2: drop the old column
ALTER TABLE orders DROP COLUMN old_ref;
```

### Changing a column type

Always use the rename pattern above — add a new column with the desired type, migrate data, drop the old.

## Locking Patterns

| Pattern | Use case |
|---------|---------|
| `SELECT ... FOR UPDATE` | State machine transitions — lock one row, check state, update atomically |
| `SELECT ... FOR UPDATE NOWAIT` | Race resolution — fail fast if already locked |
| `SELECT ... FOR UPDATE SKIP LOCKED` | Outbox pollers — multiple tasks each grab different rows, no contention |
| `pg_advisory_lock(key)` | Cross-task coordination (e.g., saga timeout enforcer scheduled task) |

Outbox poll pattern:
```sql
SELECT * FROM outbox
WHERE processed_at IS NULL
ORDER BY created_at
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

## Indexing Rules

- Index every foreign key column (PostgreSQL does not do this automatically).
- Index every column that appears in a `WHERE` clause on a hot query.
- Use composite indexes when queries filter on multiple columns together.
- Name: `idx_{table}_{col1}_{col2}` — e.g., `idx_orders_user_id_created_at`.
- Periodically audit unused indexes via `pg_stat_user_indexes` and remove dead weight.

```sql
-- In the migration that adds a foreign key, also add its index:
ALTER TABLE order_items ADD CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders(id);
CREATE INDEX idx_order_items_order_id ON order_items(order_id);
```

## HikariCP Tuning for RDS

```yaml
spring:
  datasource:
    url: ${DB_URL}                         # RDS instance endpoint
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 10               # (RDS max_connections / expected task count) × 0.8
      minimum-idle: 2
      connection-timeout: 5000            # ms — fail fast rather than queue indefinitely
      idle-timeout: 300000                # 5 min
      max-lifetime: 1800000               # 30 min — must be < RDS wait_timeout
      leak-detection-threshold: 60000     # 1 min — logs warning for connections held too long
      pool-name: {service-name}-pool
```

RDS notes:
- Use the **instance endpoint** for single-instance deployments (t4g.micro — no read replica).
- RDS failover in Multi-AZ takes ~30–60 s. HikariCP's `connection-timeout` should be shorter than your
  upstream ALB/API GW timeout so the task fails fast and ECS can restart it rather than hanging requests.
- `maximum-pool-size` × (number of running tasks) must stay well under `max_connections` on the RDS instance.
  A `db.t4g.micro` has ~87 max connections by default.

## Common SQL Patterns

**Upsert (ON CONFLICT):**
```sql
INSERT INTO user_profiles (user_id, display_name, updated_at)
VALUES (?, ?, NOW())
ON CONFLICT (user_id) DO UPDATE SET display_name = EXCLUDED.display_name, updated_at = NOW()
RETURNING id;
```

**Outbox schema** — see the `outbox-pattern` skill for the canonical table definition; the migration
that creates it is `V{n}__add_outbox_table.sql`.

## Integration Test Setup (Testcontainers)

```java
@Testcontainers
@SpringBootTest
class UserRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Flyway runs its migrations against this container at test startup
    }
}
```

Use `@Sql("/test-data/users.sql")` on individual tests that need pre-existing rows.

## Example: V1__create_users.sql

```sql
CREATE TABLE users (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(255) NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_email ON users(email);
```

## Anti-Patterns — Flag These

| Anti-pattern | Why it's dangerous |
|-------------|-------------------|
| Editing a committed `V` migration file | Flyway checksum mismatch fails all task startups |
| Two migrations with the same version number | Flyway fails on startup; one file silently ignored locally |
| `DROP COLUMN` / `NOT NULL` without prior nullable-add migration | Running tasks crash reading a schema they don't understand |
| `spring.jpa.hibernate.ddl-auto` set to anything other than `none` | Use `none` — Flyway owns the schema |
| Missing `spring-boot-flyway` module in service POM | Flyway autoconfiguration won't load in Spring Boot 4 |
| Schema migrations and code changes in the same PR | Split into two PRs — deploy migration first |
| `maximum-pool-size` not accounting for task count × RDS max_connections | RDS connection exhaustion under load |
| `TIMESTAMP` / `LocalDateTime` for timestamped columns | Use `TIMESTAMPTZ` — `TIMESTAMP` silently drops timezone |
| Long-running `ALTER TABLE ... ADD COLUMN NOT NULL` on large tables | Locks the table; use nullable-first approach |
