# aws-microservices-portfolio — Build Plan for Claude Code

> A step-by-step plan to build a production-style AWS portfolio project using Java 25 + Spring Boot 4, ECS Fargate, Maven multi-module monorepo, and OpenTofu. Designed to be executed phase-by-phase with Claude Code.

---

## 1. Project Overview

### Goal
Build a small but realistic distributed system on AWS that demonstrates: load balancing, SQL + NoSQL persistence, caching, async messaging, gRPC, JWT auth, auto-scaling, file uploads, observability, and Infrastructure as Code.

### Why a Monorepo
This project keeps **all services, shared code, infrastructure, tests, and docs in a single repository**. For a portfolio project this is unambiguously the right call:

- **One link on the CV.** Recruiters click once and see everything.
- **Atomic cross-service changes.** Update a shared `.proto` and all consumers in one PR.
- **One CI pipeline, one set of tooling, one architecture diagram.**
- **Less ceremony than 4–6 repos for a 4-service project.**

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
                                         │   │  gRPC via Cloud Map
                                         │   ▼
                                    ┌────▼─────────┐
                                    │ SNS → SQS    │
                                    │ (async events)│
                                    └──────────────┘
```

### Communication Patterns (mental model)

| Need | Tool |
|---|---|
| "I need this data fast, I'll wait for it" | **Redis** (cache-aside) |
| "I need to call another service and get an answer now" | **gRPC** (sync RPC, internal only) |
| "I need to tell other services something happened, but I don't want to wait" | **SNS → SQS** (async events) |
| "I need to permanently store this" | **RDS / DynamoDB / S3** |

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
| Async | SNS → SQS |
| Files | S3 (presigned URLs) |
| Secrets | AWS Secrets Manager + SSM Parameter Store |
| Registry | ECR |
| Logs | CloudWatch Logs |
| Metrics | CloudWatch Metrics via Micrometer |
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
- **Spring Boot 4** managed via the BOM in `<dependencyManagement>` (not as a parent — keeps the parent POM ours).
- **AWS SDK v2 BOM** in `<dependencyManagement>` so all child modules share versions.
- **Spring Cloud AWS BOM** for `@SqsListener`, Secrets Manager integration, etc.
- **Grpc-Java + protobuf-maven-plugin** declared in `proto-shared` module.
- Common plugin config (Surefire, Failsafe for IT tests, Jib or Spring Boot's `build-image` for OCI images) declared in `<pluginManagement>`.
- `mvnw` wrapper committed so reviewers don't need Maven installed.

### Module Dependencies

```
proto-shared  ──►  (no deps)
user-service  ──►  proto-shared
catalog-service ──►  proto-shared
order-service ──►  proto-shared
file-service  ──►  proto-shared
```

`proto-shared` builds once at the start of every Maven reactor run; all services depend on its generated stubs.

---

## 3. Build Plan — 9 Phases

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
1. `infra/modules/network`: VPC with 2 public subnets, 2 private subnets, **single NAT Gateway** (cost optimization, document in README), Internet Gateway, route tables.
   - **Cheaper alternative:** zero NAT, use **VPC interface endpoints** for ECR (api + dkr), Secrets Manager, CloudWatch Logs, SNS, SQS, plus a **gateway endpoint** for S3 and DynamoDB. Document this choice as a deliberate cost optimization.
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
- [ ] **Complete**

**Goal:** all four services live, each independently scalable. gRPC and SNS/SQS wired up.

**Tasks per service:**

**`catalog-service` (DynamoDB + Redis)**
- DynamoDB table, on-demand billing, single-table design (PK=`pk`, SK=`sk`, GSI1 for secondary access).
- ElastiCache Redis (`cache.t4g.micro`, single node) in private subnets.
- **Cache-aside pattern:** `@Cacheable` on read paths, `@CacheEvict` on writes. TTL 5 minutes.
- gRPC server on port 9090 (in addition to HTTP 8080).
- Cloud Map service registration under namespace `internal.local`.
- ALB listener rule for `/catalog/*`.

**`order-service` (SNS → SQS + gRPC client)**
- SNS topic `orders-events`.
- SQS queue `orders-processing` subscribed to the SNS topic (plus DLQ with `maxReceiveCount=3`).
- Spring Cloud AWS `@SqsListener` consuming from the queue.
- gRPC client → calls `catalog-service` via Cloud Map DNS (`catalog-service.internal.local:9090`).
- **The flow:** `POST /orders` → save order in Postgres → publish `OrderCreated` to SNS → return 201. A `@SqsListener` in the same service (or a worker) consumes the event and calls `catalog-service` via gRPC to decrement stock.
- ALB listener rule for `/orders/*`.

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
- [ ] **Complete**

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
- [ ] **Complete**

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
- [ ] **Complete**

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

### Phase 8 — Polish for the CV
- [ ] **Complete**

**Goal:** the repo *is* the artifact. Make it readable in 3 minutes.

**Tasks:**
1. **README.md** with this structure:
   - One-line project description.
   - Hero architecture diagram (PNG in `docs/diagrams/`).
   - **AWS services used** — bulleted list (recruiters skim for keywords).
   - **Quickstart:** `./scripts/up.sh` → `./scripts/get-token.sh` → curl example → `./scripts/down.sh`.
   - **Cost breakdown table** (always-on vs torn-down).
   - **Screenshots:** ECS console, X-Ray service map, CloudWatch dashboard, scaling event, k6 output.
   - **Demo video link** (3–5 min Loom/YouTube).
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
3. **Demo video** (3–5 min): walk the architecture diagram, run a request, show the trace in X-Ray, trigger scaling with k6.
4. Pin the repo on your GitHub profile. Link from CV and LinkedIn.

**Exit criteria:** a stranger can read the README in 3 minutes and understand what you built, why, and what you'd do differently at scale.

---

## 4. Working with Claude Code

### Recommended workflow
- **One phase per session.** Don't ask for all 9 at once — context bloats and quality drops.
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

- [ ] `./scripts/up.sh` provisions everything from zero in under 20 minutes.
- [ ] `./mvnw verify` is green from repo root.
- [ ] End-to-end k6 flow passes: sign up → log in → create catalog item → place order → upload file.
- [ ] Auto-scaling triggers under k6 load and you have a screenshot.
- [ ] CloudWatch dashboard shows live metrics from all 4 services.
- [ ] X-Ray service map shows traces spanning all services + Dynamo + Redis.
- [ ] CI pipeline deploys on merge to main with no manual steps.
- [ ] `./scripts/down.sh` removes everything (verify zero unexpected charges next day).
- [ ] README has architecture diagram, AWS-services bullet list, tradeoffs section, demo video link.
- [ ] At least 5 ADRs written.
- [ ] Repo pinned on GitHub profile, linked from CV.

---

## 6. Stretch Goals (only after Definition of Done)

In order of CV value:
1. **WAF in front of API Gateway** — one rate-limit rule, trivial to add, recognizable.
2. **Canary deploys** via CodeDeploy + ECS — progressive rollout.
3. **Multi-AZ RDS** — flip a flag, mention in README.
4. **DynamoDB Streams → Lambda** — adds an event-driven serverless flow.
5. **EventBridge** for one cross-service flow — modern messaging.
6. **Small React frontend** calling API Gateway — makes the demo video much more compelling.
7. **Grafana Cloud** for the dashboard instead of CloudWatch — recognizable, free tier.

---

## 7. Things to NOT Do (scope discipline)

- ❌ Multi-region. Out of scope.
- ❌ Kubernetes / EKS. ECS is the point.
- ❌ Service mesh (App Mesh, Istio). Cloud Map is enough.
- ❌ Self-hosted anything (Rabbit, Kafka, Vault, Prometheus). Use AWS-managed.
- ❌ More than 4 services. Resist the urge.
- ❌ A custom auth service. Cognito does this.
- ❌ Aurora at this stage. Plain RDS is the right choice for a portfolio.
- ❌ Split into multiple repos. Monorepo by design.
- ❌ Custom Gradle build logic. Maven is fine, standard, and recruiter-friendly.

---

**Start with Phase 0. Good luck.**
