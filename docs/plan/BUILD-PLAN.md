# aws-microservices-portfolio — Build Plan for Claude Code

> A step-by-step plan to build a production-style AWS portfolio project using Java 25 + Spring Boot 4, ECS Fargate, Maven multi-module monorepo, and OpenTofu. Designed to be executed phase-by-phase with Claude Code.

---

## 1. Project Overview

### Goal
Build a small but realistic distributed system on AWS that demonstrates: load balancing, SQL + NoSQL persistence, caching, async messaging, gRPC, JWT auth, auto-scaling, file uploads, observability, and Infrastructure as Code.

### Why a Monorepo
This project keeps **all services, shared code, infrastructure, tests, and docs in a single repository**.

- **Atomic cross-service changes.** Update a shared `.proto` and all consumers in one PR.
- **One CI pipeline, one set of tooling, one architecture diagram.**
- **Less ceremony than 5–6 repos for a 5-service project.**

> In the README, explicitly note: "Monorepo by choice for this project; in a larger org with multiple teams I'd consider splitting based on ownership boundaries." That sentence demonstrates understanding of the tradeoff.

### Architecture Summary

```
                  ┌────────────────────────────┐
   Internet ────► │  API Gateway (HTTP API)    │
                  │  + Cognito JWT Authorizer  │
                  └─────────────┬──────────────┘
                                │ VPC Link
                                ▼
                  ┌────────────────────────────┐
                  │  Application Load Balancer │ (path-routed)
                  └──┬─────────┬─────────┬─────┘
                     │         │         │
            ┌────────▼──┐ ┌────▼─────┐ ┌─▼─────────┐ ┌──────────┐
            │  user-    │ │ catalog- │ │  order-   │ │  file-   │
            │  service  │ │ service  │ │  service  │ │ service  │
            └────┬──────┘ └────┬─────┘ └─┬───┬─────┘ └────┬─────┘
                 │             │         │   │            │
            ┌────▼────┐  ┌─────▼────┐    │   │       ┌────▼────┐
            │   RDS   │  │ DynamoDB │    │   │       │   S3    │
            │PostgreSQL│  │ + Redis  │   │   │       └─────────┘
            └─────────┘  └──────────┘    │   │
                                         │   │  gRPC (sync, Cloud Map DNS)
                                         │   ▼
                                    ┌────▼──────────────┐
                                    │  payment-service  │
                                    │  (gRPC :9090,     │
                                    │   no ALB)         │
                                    └────────┬──────────┘
                                             │
                                        ┌────▼────┐
                                        │   RDS   │
                                        │PostgreSQL│
                                        └─────────┘
                                    SNS (OrderConfirmed,
                                    outbox-guaranteed)
```

### Tech Stack

| Layer | Choice |
|---|---|
| Language | Java 25 (LTS) |
| Framework | Spring Boot 4 |
| Build | **Maven multi-module** |
| Container | Docker, multi-stage builds |
| Orchestration | ECS Fargate |
| IaC | OpenTofu |
| CI/CD | GitHub Actions (OIDC, no long-lived AWS keys) |
| Migrations | Flyway |
| Auth | Amazon Cognito + Spring Security (OAuth2 Resource Server) |
| Public Edge | API Gateway HTTP API |
| Load Balancing | ALB (single, path-routed) |
| Service Discovery | AWS Cloud Map (for gRPC) |
| SQL | RDS PostgreSQL (`db.t4g.micro`, single-AZ) |
| NoSQL | DynamoDB (on-demand) |
| Cache | ElastiCache Redis (`cache.t4g.micro`, single node) |
| Async | SNS (outbox-guaranteed delivery) |
| Payments | gRPC + Strategy pattern (CREDIT_CARD / PAYPAL / BANK_TRANSFER) |
| Resilience | Saga pattern + compensating transactions + Outbox |
| Files | S3 (presigned URLs) |
| Secrets | AWS Secrets Manager + SSM Parameter Store |
| Registry | ECR |
| Logs | CloudWatch Logs + Amazon OpenSearch Service (ELK) |
| Metrics | CloudWatch Metrics via Micrometer + Grafana dashboards |
| Traces | AWS X-Ray via ADOT (OpenTelemetry) |
| Unit/IT tests | JUnit 5 + Testcontainers + WireMock |
| Load tests | **k6** |

### Cost Expectations
- **Always-on:** ~$50–80/month
- **Torn down when idle (`tofu destroy`):** ~$2–5/month
- Treat `tofu destroy` as the default state. Spin up only when developing, demoing, or recording.

---

## 2. Repository Structure (Maven Multi-Module Monorepo)

```
aws-microservices-portfolio/
├── README.md
├── pom.xml                          # PARENT POM — pins versions, declares modules
├── .gitignore
├── .editorconfig
│
├── docs/
│   ├── architecture.md
│   ├── decisions/                   # ADRs (one .md per major decision)
│   └── diagrams/                    # PNG/SVG architecture diagrams
│
├── proto-shared/                    # Maven module: shared gRPC .proto + generated stubs
│   ├── pom.xml
│   └── src/main/proto/
│       ├── catalog.proto
│       ├── payment.proto
│       └── user.proto
│
├── user-service/                    # Maven module
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/
│       ├── main/java/...
│       ├── main/resources/
│       │   ├── application.yml
│       │   └── db/migration/        # Flyway SQL
│       └── test/java/...
│
├── catalog-service/                 # Maven module — DynamoDB + Redis
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/...
│
├── order-service/                   # Maven module — SNS/SQS + gRPC client
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/...
│
├── file-service/                    # Maven module — S3 presigned URLs
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/...
│
├── payment-service/                 # Maven module — gRPC server, Strategy-pattern payment methods
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/...
│
├── infra/                           # OpenTofu — all AWS infrastructure
│   ├── envs/
│   │   └── dev/
│   │       ├── main.tf
│   │       ├── variables.tf
│   │       ├── outputs.tf
│   │       └── backend.tf           # S3 + DynamoDB remote state
│   └── modules/
│       ├── network/                 # VPC, subnets, SGs, endpoints
│       ├── ecr/
│       ├── cognito/
│       ├── api-gateway/
│       ├── alb/
│       ├── ecs-cluster/
│       ├── ecs-service/             # reusable: one per microservice
│       ├── cloud-map/
│       ├── rds-postgres/
│       ├── dynamodb/
│       ├── elasticache-redis/
│       ├── sns-sqs/
│       ├── s3-bucket/
│       ├── secrets/
│       ├── observability/           # log groups, dashboards, alarms
│       ├── opensearch/              # Amazon OpenSearch Service domain (ELK)
│       └── github-oidc/             # CI/CD trust policy
│
├── tests/
│   └── load/                        # k6 scripts
│       ├── smoke.js                 # 1 VU, 30s — runs in CI on every PR
│       ├── scale.js                 # ramping load to trigger auto-scaling
│       └── order-flow.js            # full E2E happy path
│
├── .github/
│   └── workflows/
│       ├── ci.yml                   # Maven build, unit/IT tests, push images
│       ├── load-test.yml            # k6 smoke on PR (against ephemeral env)
│       └── infra.yml                # tofu fmt/validate/plan (apply on dispatch)
│
└── scripts/
    ├── up.sh                        # tofu apply + smoke test
    ├── down.sh                      # tofu destroy
    ├── build-and-push.sh            # mvn package + docker build + ECR push
    └── get-token.sh                 # helper: get a Cognito JWT for curl
```

### Parent `pom.xml` Conventions

- **Java 25** via `<maven.compiler.release>25</maven.compiler.release>`.
- **Spring Boot 4** managed via the BOM in `<dependencyManagement>`.
- **AWS SDK v2 BOM** in `<dependencyManagement>` so all child modules share versions.
- **Spring Cloud AWS BOM** for SNS/Secrets Manager integration, etc.
- **Grpc-Java + protobuf-maven-plugin** declared in `proto-shared` module.
- Common plugin config (Surefire, Failsafe for IT tests, Jib or Spring Boot's `build-image` for OCI images) declared in `<pluginManagement>`.
- `mvnw` wrapper committed so reviewers don't need Maven installed.

### Module Dependencies

```
proto-shared     ──►  (no deps)
user-service     ──►  proto-shared
catalog-service  ──►  proto-shared
order-service    ──►  proto-shared
file-service     ──►  proto-shared
payment-service  ──►  proto-shared
```

`proto-shared` builds once at the start of every Maven reactor run; all services depend on its generated stubs.

---

## 3. Build Plan — 13 Phases

> Each phase is a self-contained Claude Code task. Complete and verify each before moving on. Commit after every phase.

---

### Phase 0 — Local Foundation (no AWS yet)
- [x] **Complete**

**Goal:** working Maven multi-module monorepo, `user-service` runs locally, you can `docker compose up` Postgres and Redis.

**Tasks:**
1. Init repo, add `.gitignore` (Java, Maven `target/`, Terraform `.tfstate*`, IDE, `.env`).
2. Add `.editorconfig` (2-space YAML, 4-space Java, LF endings).
3. Create the directory structure from section 2.
4. Generate Maven wrapper: `mvn -N wrapper:wrapper`.
5. **Parent `pom.xml`:**
   - `<packaging>pom</packaging>`, declare all child modules.
   - `<dependencyManagement>` imports: Spring Boot BOM, Spring Cloud AWS BOM, AWS SDK BOM, grpc-bom, testcontainers-bom.
   - `<pluginManagement>` for Surefire, Failsafe, Spring Boot plugin.
6. **`proto-shared` module:** add `protobuf-maven-plugin`, one starter `.proto`, verify `mvn -pl proto-shared clean install` generates Java stubs.
7. **`user-service` module:**
   - Dependencies: `spring-boot-starter-web`, `actuator`, `data-jpa`, `validation`, `flyway-core`, `flyway-database-postgresql`, `oauth2-resource-server`, `spring-cloud-aws-starter-secrets-manager`, `micrometer-registry-cloudwatch2`, `postgresql` driver.
   - Test deps: `spring-boot-starter-test`, `testcontainers-postgresql`, `wiremock-standalone`.
   - `Application.java`, one `UserController` with `POST /users`, `GET /users/{id}`.
   - Flyway migration `V1__init.sql` creating `users` table.
   - `application.yml` with profiles `local` (localhost DB) and `aws` (Secrets Manager).
8. **`docker-compose.yml`** for local dev: Postgres 16, Redis 7, LocalStack (optional, for SNS/SQS emulation).
9. **`Dockerfile`** for `user-service` — multi-stage, Eclipse Temurin 25, distroless or `temurin:25-jre` runtime, non-root user.
10. Verify: `./mvnw verify` passes, `docker compose up` + `./mvnw -pl user-service spring-boot:run` → curl works, Flyway migrates cleanly.

**Exit criteria:** `./mvnw verify` green from the repo root. Local user CRUD works against dockerized Postgres.

---

### Phase 1 — Networking + ECR (the AWS foundation)
- [x] **Complete**

**Goal:** a VPC and a place to push images. Nothing running yet.

**Tasks:**
1. `infra/modules/network`: VPC with 2 public subnets, 2 private subnets, **single NAT Gateway**, Internet Gateway, route tables. **Free S3 and DynamoDB Gateway endpoints** added to keep ECR image-layer pulls and DynamoDB traffic off the NAT data-processing bill.
2. `infra/modules/ecr`: 4 repositories (`user-service`, `catalog-service`, `order-service`, `file-service`), image scanning enabled, lifecycle policy "keep last 10 images".
3. `infra/envs/dev/backend.tf`: S3 backend + DynamoDB state lock table (bootstrap these two manually first, then everything else via OpenTofu).
4. `infra/envs/dev/main.tf`: wire `network` + `ecr` modules.
5. `tofu init && tofu plan && tofu apply`, verify in console.
6. `scripts/build-and-push.sh`: builds all services (`./mvnw -T 1C clean package`), tags images with git SHA, pushes to ECR.

**Exit criteria:** `aws ecr list-images --repository-name user-service` shows your image. `tofu destroy` cleans up everything.

---

### Phase 2 — Cognito + API Gateway (auth at the edge)
- [x] **Complete**

**Goal:** issue and validate JWTs *before* any backend exists. Test with curl.

**Tasks:**
1. `infra/modules/cognito`:
   - User Pool with email login, password policy, MFA optional.
   - App client (no client secret — public client for SPA-style flows).
   - Cognito Hosted UI domain.
   - Outputs: issuer URI, JWKS URI, app client ID, user pool ID.
2. `infra/modules/api-gateway`:
   - HTTP API (not REST API — cheaper, simpler, supports JWT authorizers natively).
   - JWT authorizer pointing at Cognito issuer + audience.
   - Temporary mock integration on `/health` returning 200.
3. `scripts/get-token.sh`: helper that creates a test user, sets a password, runs `initiate-auth`, prints the ID token.
4. Verify: curl without token → 401; curl with token → 200.

**Exit criteria:** JWT flow works end-to-end against AWS. No Java involved yet.

---

### Phase 3 — First service on Fargate behind ALB
- [x] **Complete**

**Goal:** `user-service` deployed to ECS Fargate, behind ALB, behind API Gateway.

**Tasks:**
1. `infra/modules/alb`: internal ALB (since API Gateway will reach it via VPC Link), security groups, default 404 listener.
2. `infra/modules/rds-postgres`: `db.t4g.micro`, single-AZ, encrypted, in private subnets. Master credentials → Secrets Manager. Output the secret ARN.
3. `infra/modules/secrets`: any non-DB secrets here (e.g., third-party API keys later).
4. `infra/modules/ecs-cluster`: ECS cluster, Container Insights enabled, default capacity providers `FARGATE` and `FARGATE_SPOT`.
5. `infra/modules/ecs-service` (reusable): inputs = image URI, port, env vars, secrets ARNs map, CPU/memory, desired count, ALB listener ARN, path pattern, health check path.
   - Creates: task definition, service, target group, listener rule, IAM task role + execution role, log group.
   - Outputs: service name (for auto-scaling later).
6. `infra/modules/api-gateway` update: add VPC Link → ALB, integration for `/users/*`.
7. **`user-service` Spring config:**
   - `spring.cloud.aws.secretsmanager.endpoint` + secret name resolves DB credentials.
   - `spring.security.oauth2.resourceserver.jwt.issuer-uri` = Cognito issuer.
   - `management.endpoints.web.exposure.include=health,info,prometheus,metrics`.
   - Flyway runs on startup automatically.
8. Push image, `tofu apply`, verify task is healthy in ECS console.
9. **End-to-end test:** `./scripts/get-token.sh` → `curl -H "Authorization: Bearer $TOKEN" https://<api-gw>/users` → 200, row in RDS.

**Exit criteria:** authenticated request creates a user in RDS via the full path API GW → ALB → Fargate → RDS. CloudWatch Logs show the request.

---

### Phase 4 — Remaining services
- [x] **Complete**

**Goal:** all four services live, each independently scalable. gRPC and SNS/SQS wired up.

**Tasks per service:**

**`catalog-service` (DynamoDB + Redis)**
- DynamoDB table, on-demand billing, single-table design (PK=`pk`, SK=`sk`, GSI1 for secondary access).
- ElastiCache Redis (`cache.t4g.micro`, single node) in private subnets.
- **Cache-aside pattern:** `@Cacheable` on read paths, `@CacheEvict` on writes. TTL 5 minutes.
- gRPC server on port 9090 (in addition to HTTP 8080).
- Cloud Map service registration under namespace `internal.local`.
- ALB listener rule for `/catalog/*`.

**`order-service` (gRPC orchestrator + SNS)**
- SNS topic `orders-events` (SQS queue + DLQ provisioned in OpenTofu for future consumers).
- gRPC client → `catalog-service` via Cloud Map DNS (`catalog-service.internal.local:9090`).
- gRPC client → `payment-service` via Cloud Map DNS (`payment-service.internal.local:9090`).
- **The flow:** `POST /orders` → save PENDING → sync gRPC payment → sync gRPC stock decrement → save CONFIRMED → publish `OrderConfirmed` to SNS → return 201.
- ALB listener rule for `/orders/*`.

> **Note:** The original async flow (`OrderCreated` → SQS consumer → DecrementStock) was replaced in Phase 8 with synchronous gRPC orchestration. The SQS consumer (`@SqsListener`, `SqsMessagePoller`) was removed. Phase 9 adds a full saga with compensation and an outbox for reliable SNS delivery.

**`file-service` (S3 presigned URLs)**
- S3 bucket: private, versioned, SSE-S3 encryption, public access blocked, lifecycle policy "delete incomplete multipart uploads after 7 days".
- Endpoints: `POST /files/presign-upload` → returns presigned PUT URL; `GET /files/{id}/presign-download` → returns presigned GET URL.
- IAM task role: least-privilege S3 (only this bucket, only `GetObject`/`PutObject`).
- ALB listener rule for `/files/*`.

**Shared additions:**
- All services pull DB creds / config from Secrets Manager + SSM Parameter Store.
- All services register with Cloud Map (even HTTP-only ones — uniform pattern).
- Add k6 `order-flow.js` exercising the full happy path: sign up → log in → create catalog item → place order → upload file.

**Exit criteria:** k6 `order-flow.js` passes end-to-end. CloudWatch shows logs from all 4 services. SQS shows messages flowing.

---

### Phase 5 — Auto-scaling
- [x] **Complete**

**Goal:** every service scales independently on real signals.

**Tasks:**
1. For each ECS service, configure **Application Auto Scaling**:
   - **Primary policy: target tracking on `ALBRequestCountPerTarget`** — target 50 req/min/task (low number so you can demo scaling easily).
   - **Secondary policy: target tracking on CPU at 70%**.
   - `min_capacity = 1`, `max_capacity = 3`.
2. **Scheduled scaling** (huge cost saver):
   - Scale to `min=0, max=0` at 22:00.
   - Scale to `min=1, max=3` at 08:00.
   - Document in README as a portfolio-specific cost optimization.
3. Run k6 `scale.js` (ramping load), watch tasks scale out in CloudWatch.
4. Screenshot the scaling event → save to `docs/diagrams/scaling-event.png` for the README.

**Exit criteria:** generated load triggers scale-out within ~3 minutes; CloudWatch scaling activities visible; screenshot captured.

---

### Phase 6 — Observability
- [x] **Complete**

**Goal:** logs, metrics, traces, one dashboard, a few alarms.

**Tasks:**
1. **Logs:** confirm all 4 services ship stdout → CloudWatch Logs (awslogs driver). One log group per service, 7-day retention.
2. **Metrics:**
   - `micrometer-registry-cloudwatch2` in each service.
   - Custom business metrics (e.g. `orders.created.count`, `files.uploaded.bytes`).
   - Container Insights enabled (already done in Phase 3).
3. **Traces — ADOT:**
   - Run the AWS Distro for OpenTelemetry collector as a **sidecar container** in each task definition.
   - Java services use the **OTel auto-instrumentation Java agent** (added via `JAVA_TOOL_OPTIONS=-javaagent:/otel/javaagent.jar`).
   - Env vars: `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317`, `OTEL_TRACES_EXPORTER=otlp`, `OTEL_SERVICE_NAME=<service>`.
   - Collector exports to X-Ray.
4. **Dashboard:** one CloudWatch dashboard with:
   - Requests/min per service.
   - p50 / p99 latency per service.
   - ECS CPU and memory per service.
   - RDS connections + CPU.
   - SQS queue depth and oldest message age.
   - DynamoDB consumed RCU/WCU.
5. **Alarms** (publish to SNS topic, subscribe email):
   - 5xx rate > 5% for 2 minutes per service.
   - SQS `ApproximateAgeOfOldestMessage` > 60s.
   - SQS DLQ has any messages.
   - RDS CPU > 80%.
   - ECS service running count < desired count.
6. Verify in X-Ray: a single trace spans API GW → ALB → user-service → gRPC → catalog-service → DynamoDB → Redis.
7. Screenshot dashboard + X-Ray service map → `docs/diagrams/`.

**Exit criteria:** distributed trace visible end-to-end. Dashboard and service map screenshots captured.

---

### Phase 7 — CI/CD
- [x] **Complete**

**Goal:** push to `main` → image built, pushed to ECR, ECS service updated. No long-lived AWS credentials anywhere.

**Tasks:**
1. **`infra/modules/github-oidc`:** IAM OIDC provider for `token.actions.githubusercontent.com`, role with trust policy scoped to your repo, permissions for ECR push + `ecs:UpdateService`.
2. **`.github/workflows/ci.yml`:**
   - Triggers: PR + push to main.
   - `setup-java@v4` (Temurin 25), Maven cache.
   - `./mvnw -T 1C verify` (unit + IT tests via Testcontainers).
   - On push to main: `aws-actions/configure-aws-credentials` (OIDC), build images, tag with commit SHA + `latest`, push to ECR, `aws ecs update-service --force-new-deployment` for each changed service.
   - **Optimization:** detect changed modules via `git diff --name-only` and only rebuild/push those.
3. **`.github/workflows/load-test.yml`:**
   - On PR (manual dispatch initially), spin up k6 in a job, run `tests/load/smoke.js` against the dev environment.
4. **`.github/workflows/infra.yml`:**
   - On PR: `tofu fmt -check`, `tofu validate`, `tofu plan` (comment the plan output on the PR).
   - On manual dispatch: `tofu apply`.
   - Never auto-apply on push — safer.

**Exit criteria:** merge a PR to main → rolling deployment visible in ECS within 5 minutes, zero manual steps, zero long-lived secrets.

---

### Phase 8 — Payment Service
- [x] **Complete**

**Goal:** Add `payment-service` as a fifth microservice — synchronously callable via gRPC from `order-service`. Demonstrates multi-service gRPC orchestration, the Strategy pattern for payment methods, and transactional flow design (sync payment call → async SNS notifications downstream).

**Architecture note:** Payment is synchronous in the request path — customers need immediate success/failure feedback. The flow: `POST /orders` → sync gRPC to payment-service → on success: CONFIRMED + DecrementStock gRPC (catalog) + SNS publish; on failure: FAILED + return 402.

**Tasks:**

1. **`payment.proto`** — add to `proto-shared/src/main/proto/`:
   - `ProcessPayment(PaymentRequest) returns (PaymentResponse)`
   - Enums: `PaymentMethod` (CREDIT_CARD, PAYPAL, BANK_TRANSFER), `PaymentStatus` (SUCCESS, FAILED)
   - Rebuild stubs: `./mvnw -pl proto-shared clean install`

2. **`payment-service` Maven module:**
   - Add to parent `pom.xml` and update all sibling Dockerfiles (new `COPY` for pom.xml — layer caching rule from `CLAUDE.md`).
   - Dependencies: `spring-boot-starter-web`, `actuator`, `data-jpa`, `validation`, `flyway-core`, `flyway-database-postgresql`, `spring-cloud-aws-starter-secrets-manager`, gRPC server libs, `proto-shared`.
   - Apply all SB4 workarounds from `CLAUDE.md`.

3. **Domain:**
   - `PaymentStrategy` interface: `PaymentResult process(PaymentRequest)`.
   - `CreditCardPaymentStrategy`, `PayPalPaymentStrategy`, `BankTransferPaymentStrategy` — all stubbed (no real payment gateway — portfolio context).
   - `PaymentGrpcService extends PaymentServiceGrpc.PaymentServiceImplBase` — dispatches via `Map<PaymentMethod, PaymentStrategy>` bean.
   - `PaymentRecord` JPA entity.

4. **Flyway:** `V1__create_payment_records.sql` — `payment_records(id, order_id, amount, currency, method, status, failure_reason, created_at)`.

5. **`application.yml`** — local + aws profiles, gRPC server port 9090, HTTP 8080. Apply SB4 workarounds 1, 5, 11.

6. **Dockerfile** — multi-stage, OTel Java agent (same pattern as other services, see `CLAUDE.md` workaround 12).

7. **Update `order-service`:**
   - Add `payment-service` gRPC client channel via Cloud Map DNS (`payment-service.internal.local:9090`).
   - Revise `POST /orders` flow:
     1. Save order → `PENDING`.
     2. gRPC `ProcessPayment`.
     3. SUCCESS: update → `CONFIRMED`, gRPC `DecrementStock`, publish `OrderConfirmed` to SNS, return 201.
     4. FAILURE: update → `FAILED`, return 402.

8. **Infrastructure (OpenTofu):**
   - ECR repo: `portfolio-dev-payment-service`.
   - ECS service on Fargate — **no ALB listener rule** (internal gRPC only, no public HTTP path).
   - Cloud Map service registration: `payment-service.internal.local`.
   - IAM task role: Secrets Manager read for RDS credentials.
   - CloudWatch log group: `/ecs/portfolio-dev-payment-service`.

9. **Business metrics:** `payment.attempts.total`, `payment.success.total`, `payment.failure.total` — tagged by `method` (credit_card / paypal / bank_transfer).

10. **CI/CD:** add `payment-service` to git-diff change detection in `ci.yml`.

11. **k6 `order-flow.js`:** update happy path to assert 201 on success; add a variant asserting 402 on forced payment failure.

**Exit criteria:** `POST /orders` synchronously calls payment-service via gRPC. Success → order CONFIRMED, stock decremented, SNS event published. Failure → HTTP 402. Payment records in RDS. `./mvnw verify` green with IT tests for payment-service.

---

### Phase 9 — Saga Pattern + Outbox
- [x] **Complete**

**Goal:** Make the `POST /orders` flow resilient to partial failures across service boundaries. Introduce an explicit order state machine (PENDING → PAID → CONFIRMED), a compensating transaction (refund) when stock decrement fails, and the outbox pattern to guarantee SNS event delivery survives pod crashes.

**Problem being solved:**
- Current flow calls payment gRPC, then stock gRPC, then publishes SNS — all in one `@Transactional`. If stock decrement fails after payment succeeded, the `@Transactional` rollback only affects order-service's DB. Payment record in payment-service is already committed — customer is charged, order is not confirmed.
- If the pod crashes after `save(CONFIRMED)` but before `publishOrderConfirmed()`, the SNS event is lost — downstream systems never hear about it.

**State machine:**
```
PENDING → PAID → CONFIRMED
   │         │
   ▼         ▼
FAILED  COMPENSATING → FAILED
```

**Tasks:**

1. **`OrderStatus`** — add `PAID`, `COMPENSATING`.

2. **`payment.proto`** — add `RefundPayment` RPC to `proto-shared`:
   ```protobuf
   rpc RefundPayment(RefundRequest) returns (RefundResponse);
   message RefundRequest  { string payment_id = 1; string reason = 2; }
   message RefundResponse { bool success = 1; }
   ```
   Rebuild stubs: `./mvnw -pl proto-shared clean install`.

3. **Flyway migrations in order-service:**
   - `V2__add_order_paid_status.sql` — add `payment_id VARCHAR(36)` column to `orders`.
   - `V3__create_outbox_events.sql`:
     ```sql
     CREATE TABLE outbox_events (
         id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
         aggregate_type VARCHAR(100) NOT NULL,
         aggregate_id   VARCHAR(100) NOT NULL,
         event_type     VARCHAR(100) NOT NULL,
         payload        TEXT         NOT NULL,
         created_at     TIMESTAMP    NOT NULL DEFAULT now(),
         published_at   TIMESTAMP
     );
     CREATE INDEX idx_outbox_unpublished ON outbox_events(created_at)
         WHERE published_at IS NULL;
     ```

4. **`Order` entity** — add `paymentId` field.

5. **`OutboxEvent` JPA entity + `OutboxEventRepository`** — `findTop10ByPublishedAtIsNullOrderByCreatedAtAsc()`.

6. **`PaymentGrpcClient`** in order-service — add `refundPayment(String paymentId, String reason)` method using the new proto stub.

7. **`PaymentGrpcService`** in payment-service — implement `refundPayment`: find the `PaymentRecord` by id, set status `REFUNDED`, save.

8. **`OrderService.createOrder()`** — rewrite as explicit saga steps. Remove `@Transactional` from the method itself (each `save()` commits independently as a durable checkpoint):
   ```
   save(PENDING)                              ← checkpoint 1
   gRPC processPayment()
     └─[FAILED] → save(FAILED), throw 402
   save(PAID + paymentId)                     ← checkpoint 2 — money taken
   gRPC decrementStock() per item
     └─[FAILED] → compensate():
           save(COMPENSATING)
           gRPC refundPayment()
             └─[FAILED] → log ERROR, leave COMPENSATING (recovery job handles it)
           save(FAILED), throw 500
   @Transactional: save(CONFIRMED) + outbox row  ← checkpoint 3 — atomic
   return 201
   ```

9. **`OutboxPoller`** — `SmartLifecycle` that runs every 5 seconds:
   - Fetch up to 10 unpublished outbox rows.
   - `snsTemplate.sendNotification(topicArn, payload, eventType)` per row.
   - `outboxEventRepository.markPublished(id, Instant.now())` on success.
   - On SNS failure: log and leave unpublished — next poll retries automatically.

10. **`OrderRecoveryJob`** — `@Scheduled(fixedDelay = 300_000)` (every 5 min):
    - `SELECT * FROM orders WHERE status = 'COMPENSATING' AND updated_at < now() - interval '5 minutes'`.
    - Retry `paymentGrpcClient.refundPayment()` for each.
    - On success: set `FAILED`, save.
    - On failure: log ERROR with orderId + paymentId for manual intervention.

11. **IT tests** — update `OrderControllerIT`:
    - Happy path: assert final status `CONFIRMED`, assert outbox row exists and `published_at` is set after poller runs.
    - Payment failure: assert status `FAILED`, no outbox row.
    - Stock failure (mock `CatalogGrpcClient` to throw): assert status transitions `PENDING → PAID → COMPENSATING → FAILED`, assert refund was called.
    - Recovery job: seed a `COMPENSATING` order, run job, assert `FAILED`.

**Exit criteria:** `./mvnw verify` green. Stock-failure test confirms compensation runs and order ends as `FAILED`. No `COMPENSATING` orders left after recovery job. Outbox poller delivers SNS events — confirmed via LocalStack SNS in IT tests.

---

### Phase 10 — Grafana + Amazon OpenSearch (ELK)
- [x] **Complete**

**Goal:** Supplement CloudWatch and X-Ray with two industry-standard tools: **Grafana** for rich shareable dashboards and **Amazon OpenSearch Service** for centralized log search and analytics (ELK pattern). Both are high-visibility CV keywords and demonstrate the ability to wire external observability tooling into an AWS-managed platform.

**Tasks:**

**Track A — Grafana**

1. **Deploy Grafana:**
   - **Recommended:** Grafana Cloud free tier (SaaS — zero AWS infra cost, ~10k series free). Create a workspace at grafana.com.
   - **Alternative (more infra complexity, more CV value):** self-hosted on ECS Fargate with EFS for dashboard persistence — demonstrates stateful container deployment on Fargate.

2. **Data sources:**
   - CloudWatch: attach `CloudWatchReadOnlyAccess` IAM policy to Grafana Cloud's AWS integration.
   - X-Ray: add as a data source to visualize distributed traces alongside metrics.

3. **Dashboard — "Portfolio Overview":**
   - ALB requests/min + p50/p99 latency per service.
   - ECS CPU + memory per service.
   - RDS connections + CPU.
   - SQS visible messages + oldest message age.
   - DynamoDB consumed RCU/WCU.
   - Payment success/failure rate (from Phase 8 CloudWatch custom metrics).

4. **Export dashboard JSON** → `docs/grafana/portfolio-dashboard.json` (enables one-click re-import after `tofu destroy`).

5. Screenshot → `docs/diagrams/grafana-dashboard.png`.

**Track B — Amazon OpenSearch (ELK)**

1. **`infra/modules/opensearch`** (OpenTofu):
   - Amazon OpenSearch Service domain, engine `OpenSearch_2.x`.
   - Instance type: `t3.small.search`, single-node (cost-optimized portfolio).
   - 10 GB EBS gp3 storage.
   - VPC access mode, in private subnets.
   - Fine-grained access control enabled; master user credentials in Secrets Manager.
   - Security group: allow HTTPS (443) from ECS task security groups.
   - **Cost note:** ≈$25–30/month while running — include in `tofu destroy` and `down.sh`.

2. **Fluent Bit sidecar** on each ECS task definition (all 5 services):
   - Image: `public.ecr.aws/aws-observability/aws-for-fluent-bit:stable` (pin by digest).
   - Config: parse structured JSON app logs, enrich with `service_name` + `environment` fields, ship to OpenSearch via the `opensearch` output plugin.
   - Keep existing CloudWatch awslogs driver in parallel.

3. **OpenSearch Dashboards:**
   - Index pattern: `portfolio-logs-*` (daily indices).
   - Saved search: filterable by `service_name`, `log.level`, `trace_id`.
   - Dashboard: log volume per service (bar chart), error count, full-text search panel.

4. Screenshot → `docs/diagrams/opensearch-dashboard.png`.

**Exit criteria:** Grafana dashboard shows live CloudWatch metrics for all 5 services. OpenSearch Dashboards shows structured logs from all 5 services, searchable by service, log level, and trace ID.

---

### Phase 11 — Code Review & Performance Hardening
- [x] **Complete**

**Goal:** Audit all five services for correctness and performance issues, then apply a focused set of improvements that demonstrate senior-level thinking: right-sized connection pools, resilience patterns, reliable event publishing, Java 25 virtual threads, proper indexing, and pagination.

**Tasks:**

1. **HikariCP tuning (all services with RDS):**
   - Set `maximum-pool-size` per service based on Fargate CPU allocation. Rule of thumb for 256 CPU / 512 MB: 5 connections max. For 512 CPU: 10 max.
   - Set `minimum-idle = 2`, `connection-timeout = 3000ms`, `max-lifetime = 1800000ms` (30 min, below RDS idle timeout), `keepalive-time = 60000ms`.
   - Add `connectionTestQuery: SELECT 1` (or rely on `validation-timeout`).
   - Document pool size rationale in a code comment referencing Fargate vCPU constraint.
   - Add HikariCP metrics to CloudWatch dashboard (active/idle/pending connection counts via Micrometer).

2. **Resilience4j — circuit breakers, retries, timeouts:**
   - Add `spring-cloud-starter-circuitbreaker-resilience4j` to `order-service` and any other service making outbound gRPC calls.
   - Wrap each gRPC client call in `@CircuitBreaker(name = "catalog-grpc", fallbackMethod = ...)` + `@Retry(name = "catalog-grpc")` with exponential backoff (`waitDuration = 200ms`, `multiplier = 2`, `maxAttempts = 3`).
   - Add `@TimeLimiter(name = "catalog-grpc")` with `timeoutDuration = 2s` so a slow downstream can't hold a thread indefinitely.
   - Follow the `resilience4j-patterns` skill conventions for `application.yml` config.
   - Verify circuit breaker trips under load with a k6 test that targets a deliberately slow path.

3. **API response consistency audit:**
   - Ensure all five services return a uniform error body: `{ "error": "message", "status": 400 }`.
   - Add a `@ControllerAdvice` global exception handler (`GlobalExceptionHandler`) in each service where missing — catches `ResponseStatusException`, `MethodArgumentNotValidException`, and unexpected exceptions.
   - Audit all endpoints for consistent HTTP status codes (e.g. `POST` → 201, not 200; `DELETE` → 204; resource not found → 404 not 500).
   - Update IT tests to assert the error response shape, not just the status code.

4. **Virtual threads (Java 25):**
   - Add `spring.threads.virtual.enabled: true` to `application.yml` in all services.
   - This replaces Tomcat's and gRPC's platform thread pools with Project Loom virtual threads — significant throughput improvement for I/O-bound workloads at zero code change cost.
   - Verify with a short k6 ramp: throughput should increase, p99 latency decrease under the same Fargate CPU allocation.
   - Add a note to the README tradeoffs section: "Virtual threads (Project Loom) enabled — replaces thread-per-request overhead with lightweight continuations."

5. **Database indexes — Flyway migrations:**
   - Enable Hibernate slow query logging in dev (`logging.level.org.hibernate.SQL: DEBUG`, `spring.jpa.show-sql: true`) to surface missing indexes.
   - Add indexes where missing:
     - `user-service`: `users(email)` (unique — likely already there, confirm).
     - `order-service`: `orders(user_id)`, `orders(status)`, `orders(user_id, status)` composite.
     - `payment-service`: `payment_records(order_id)`, `payment_records(status)`.
   - Each index via a dedicated Flyway migration (`V2__add_indexes.sql`).
   - Disable slow query logging in the `aws` profile after review.

6. **Pagination on list endpoints:**
   - Add `Pageable` support to all `GET` list endpoints: `GET /users`, `GET /orders`, `GET /catalog` (DynamoDB — use `ExclusiveStartKey` for cursor pagination, not offset).
   - HTTP response shape: `{ "items": [...], "page": 0, "size": 20, "totalElements": 142 }` (SQL) or `{ "items": [...], "nextCursor": "..." }` (DynamoDB).
   - DynamoDB cursor pagination is the more interesting case — encode the `LastEvaluatedKey` as a base64 cursor in the response.
   - Update k6 scripts to assert paginated responses.

**Exit criteria:** `./mvnw verify` green across all services. HikariCP pool metrics visible in CloudWatch. Circuit breaker trips and recovers under test. Outbox poller publishes events after simulated SNS failure-then-recovery. k6 throughput measurably improved with virtual threads enabled vs disabled (document the delta). All list endpoints return paginated responses.

---

### Final Phase — Polish and Review
- [x] **Complete**

**Goal:** the repo *is* the artifact. Make it readable in 3 minutes.

**Tasks:**
1. **README.md** with this structure:
   - One-line project description.
   - Hero architecture diagram (PNG in `docs/diagrams/`).
   - **AWS services used** — bulleted list (recruiters skim for keywords).
   - **Quickstart:** `./scripts/up.sh` → `./scripts/get-token.sh` → curl example → `./scripts/down.sh`.
   - **Cost breakdown table** (always-on vs torn-down).
   - **Screenshots:** ECS console, X-Ray service map, CloudWatch dashboard, scaling event, k6 output.
   - **Architectural tradeoffs** — honest list of "what I'd change for production": multi-AZ, Aurora, EKS+Karpenter, WAF, multi-region, service mesh, multi-repo per team. *This section signals senior-level thinking and is often what gets you the interview.*
   - **Monorepo justification** — the one-sentence tradeoff acknowledgment.
2. **Architecture Decision Records** in `docs/decisions/`:
   - `001-fargate-over-ec2.md`
   - `002-api-gateway-plus-alb.md`
   - `003-cloud-map-over-service-mesh.md`
   - `004-sns-sqs-over-rabbitmq.md`
   - `005-monorepo.md`
   - `006-maven-multi-module.md`
   - `007-cognito-over-self-issued-jwt.md`
   - Each one-page: Context / Decision / Consequences / Alternatives considered.
3. Pin the repo on your GitHub profile. Link from CV and LinkedIn.
4. **Work through `docs/manual-checklist.md`** — Grafana Cloud setup, all screenshots for the README, GitHub + CV links.

**Exit criteria:** a stranger can read the README in 3 minutes and understand what you built, why, and what you'd do differently at scale. All items in `docs/manual-checklist.md` checked off.

---

## 4. Working with Claude Code

### Recommended workflow
- **One phase per session.** Don't ask for all 13 at once — context bloats and quality drops.
- **Commit after every phase.** Easy to roll back.
- **Open each session with:**
  > "We're starting Phase N. Repo state: `tree -L 3 -I target`. Plan: [paste this file]. Do Phase N. Show me each file's content before writing it, and run `tofu plan` before any apply."
- **Always review `tofu plan` output.** Have Claude explain the diff.
- **Tear down nightly** while iterating: `./scripts/down.sh`.

### Prompts that work well
- "Implement Phase 3. Start with the OpenTofu modules (`alb`, `rds-postgres`, `ecs-cluster`, `ecs-service`), then update `user-service` Spring config, then the deployment script. Show me each file before writing."
- "Review `infra/modules/ecs-service` for security issues and cost waste."
- "Write ADR 001 — fargate-over-ec2. Use the Context / Decision / Consequences / Alternatives format."
- "Write a k6 script that ramps from 1 → 50 VUs over 3 minutes against `/orders` to trigger auto-scaling."
- "The `user-service` IT test is flaky in CI. Read the Testcontainers logs and fix it."

### Guardrails
- Never run `tofu apply` without showing the plan first.
- Never commit AWS credentials, `.tfstate`, or `.env`. Remote state in S3 only.
- Pin module/provider versions (`required_version`, `required_providers`).
- Pin base Docker images by digest, not `latest`.
- Use OIDC for GitHub Actions — no long-lived IAM user keys.

---

## 5. Definition of Done

- [ ] `./scripts/up.sh` provisions everything from zero.
- [ ] `./scripts/down.sh` removes everything (verify zero unexpected charges next day).
- [ ] `./mvnw verify` is green from repo root.
- [ ] End-to-end k6 flow passes: sign up → log in → create catalog item → place order (with payment) → upload file.
- [ ] Auto-scaling triggers under k6 load and you have a screenshot.
- [ ] CloudWatch dashboard shows live metrics from all 5 services.
- [ ] Grafana dashboard live with CloudWatch data; dashboard JSON exported to `docs/grafana/`.
- [ ] OpenSearch Dashboards shows structured logs from all 5 services, searchable by service/level/trace.
- [ ] X-Ray service map shows traces spanning all services + Dynamo + Redis.
- [ ] CI pipeline deploys on merge to main with no manual steps.
- [ ] README has architecture diagram, AWS-services bullet list, tradeoffs section.
- [ ] At least 5 ADRs written.

---

## 6. Stretch Goals (only after Definition of Done)

1. **Canary deploys** via CodeDeploy + ECS — progressive rollout.
3. **Multi-AZ RDS** — flip a flag, mention in README.
4. **DynamoDB Streams → Lambda** — adds an event-driven serverless flow.
5. **EventBridge** for one cross-service flow — modern messaging.
6. **Small React frontend** calling API Gateway — makes the demo video much more compelling.
7. **Grafana Alerting** — set up alert rules in Grafana to complement CloudWatch alarms, feeding the same SNS topic.

---

## 7. Things to NOT Do (scope discipline)

- ❌ Multi-region. Out of scope.
- ❌ Kubernetes / EKS. ECS is the point.
- ❌ Service mesh (App Mesh, Istio). Cloud Map is enough.
- ❌ Self-hosted anything (Rabbit, Kafka, Vault, Prometheus). Use AWS-managed.
- ❌ More than 5 services. Resist the urge.
- ❌ A custom auth service. Cognito does this.
- ❌ Aurora at this stage. Plain RDS is the right choice for a portfolio.
- ❌ Split into multiple repos. Monorepo by design.
- ❌ Custom Gradle build logic. Maven is fine, standard, and recruiter-friendly.

---