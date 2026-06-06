# AWS Services in Use

A map of every AWS service this system relies on, what it does, and how it connects to the rest.

---

## Traffic Entry — the public front door

### API Gateway (HTTP API)
The single entry point for all external traffic. Clients call `https://<api-id>.execute-api.eu-west-1.amazonaws.com/{path}`. It handles TLS termination, JWT validation, throttling, and routing — no business logic lives here. Every route is forwarded to the internal ALB via a VPC Link.

**Connected to:** Cognito (JWT validation), VPC Link → ALB, WAF (protection layer in front of it).

### WAF (Web Application Firewall)
Sits in front of API Gateway. Inspects every incoming HTTP request and blocks common attack patterns — SQL injection, XSS, bad bots, oversized payloads — before they reach application code. Configured with AWS managed rule groups.

**Connected to:** API Gateway (attached as a Web ACL association).

### Cognito
Manages user identity. Provides a hosted sign-in/sign-up UI, issues JWT access tokens on successful login, and handles token refresh. The application itself never stores passwords.

**Connected to:** API Gateway (JWT authorizer validates tokens against Cognito's JWKS endpoint). Client apps redirect to the Cognito Hosted UI for authentication.

---

## Networking — the private backbone

### VPC (Virtual Private Cloud)
An isolated private network (`10.0.0.0/16`) that contains everything except API Gateway. All compute, databases, and caches run inside the VPC. Nothing inside is reachable from the internet unless explicitly exposed.

**Contains:** subnets, NAT Gateway, ALB, ECS tasks, RDS, ElastiCache, Cloud Map.

### Subnets
The VPC is divided into four subnets across two availability zones:
- **2 public subnets** — hold the NAT Gateway and the ALB (which needs a public IP to receive traffic from API Gateway's VPC Link).
- **2 private subnets** — hold ECS Fargate tasks, RDS, and ElastiCache. No direct internet access; outbound traffic routes through the NAT Gateway.

### NAT Gateway + Elastic IP
Allows ECS tasks in private subnets to reach the internet for outbound calls — pulling container images from ECR, sending metrics to CloudWatch, calling AWS APIs. Inbound connections from the internet cannot reach private subnets through the NAT. One NAT Gateway in one public subnet (cost vs. simplicity trade-off; a production system would have one per AZ for HA).

**Why both API Gateway and NAT Gateway?** They handle traffic in opposite directions and have nothing to do with each other:

```
Internet → API Gateway → VPC Link → ALB → ECS tasks   (inbound requests)
ECS tasks → NAT Gateway → Internet Gateway → AWS APIs  (outbound calls)
```

API Gateway is the entry point for client traffic coming in. NAT Gateway is the exit for AWS SDK calls going out. Remove API Gateway and there is no way to call the services. Remove NAT Gateway and ECS tasks cannot reach ECR or CloudWatch and will fail to start.

**Connected to:** private subnet route tables, internet gateway.

### Internet Gateway
Attaches the VPC to the internet. The NAT Gateway and ALB use it for internet-bound traffic. Without it, the VPC is completely isolated.

### Security Groups
Virtual firewalls on every resource. They control which IP ranges and ports can talk to what. For example: the ALB security group allows inbound 80/443 from anywhere; the ECS task security group allows inbound only from the ALB security group; the RDS security group allows inbound 5432 only from ECS task security groups.

### VPC Link
A private tunnel that lets API Gateway (which runs outside the VPC) route traffic to the ALB (which is inside the VPC) without the traffic ever touching the public internet. Required because the ALB is internal (private IP only).

**Connected to:** API Gateway (as an integration target), ALB.

---

## Load Balancing and Routing

### ALB (Application Load Balancer)
Receives HTTP traffic from API Gateway via the VPC Link and routes it to the correct ECS service based on path-based rules (`/users/*` → user-service, `/catalog/*` → catalog-service, etc.). Also performs health checks on ECS tasks and stops sending traffic to unhealthy ones.

**Connected to:** VPC Link (inbound), ECS target groups (outbound), ECS health checks.

### Target Groups
Each service has an ALB target group — a list of ECS task IPs on the service's port. The ALB forwards requests to one of the registered tasks (round-robin by default). In a blue/green deployment there are two target groups per service; only one has tasks registered at any given time outside of a deployment window.

---

## Compute

### ECR (Elastic Container Registry)
Private Docker image registry. CI builds and pushes a new image tag here on every merge to main. ECS pulls the image from ECR when launching a new task. Four repositories: `portfolio-dev-{user,catalog,order,file}-service`.

**Connected to:** GitHub Actions (push), ECS (pull).

### ECS (Elastic Container Service) — Fargate
Runs the four Spring Boot services as containers, without managing EC2 instances. Each service is an ECS Service (which maintains a desired task count, handles restarts, and integrates with the ALB). Each task runs the application container + an ADOT sidecar for telemetry.

**Connected to:** ECR (image source), ALB (traffic), Secrets Manager (DB credentials injection), CloudWatch Logs (log output), X-Ray (traces via ADOT sidecar), Cloud Map (service discovery), all downstream data stores.

### Application Auto Scaling
Automatically adjusts the running task count for each ECS service based on load and a schedule. Two policies per service: CPU target tracking (scales out when CPU > 70%) and ALB request count target tracking (scales out when requests/task > 50/min). Two scheduled actions per service: scale to zero at 22:00 UTC (cost saving), scale back up at 08:00 UTC.

**Connected to:** ECS services (adjusts `desired_count`), CloudWatch metrics (scaling signals).

---

## Data Stores

### RDS PostgreSQL (`db.t4g.micro`)
Relational database for `user-service` (users table) and `order-service` (orders table). Managed by Flyway migrations at application startup. Lives in the private subnet, accessible only from ECS tasks.

**Connected to:** ECS tasks (JDBC), Secrets Manager (credentials), CloudWatch (CPU/connection metrics and alarms).

### DynamoDB
NoSQL key-value store for `catalog-service`. Single-table design, on-demand billing (no provisioned capacity). Used for catalog item storage and reads. No schema migrations — table structure is defined by the application.

**Connected to:** ECS catalog-service tasks (AWS SDK v2), CloudWatch (RCU/WCU metrics).

### ElastiCache Redis (`cache.t4g.micro`)
In-memory cache used by `catalog-service` to cache DynamoDB read results. Reduces DynamoDB read load and latency for repeated catalog item lookups. Encryption in transit enabled.

**Connected to:** ECS catalog-service tasks (Lettuce client via Spring Data Redis).

### S3
Object storage for `file-service`. Stores user-uploaded files. The service never proxies file content through ECS — instead it generates presigned URLs that allow clients to upload directly to S3 (PUT) or download directly from S3 (GET), bypassing the application entirely. Configured with private access, server-side encryption (SSE-S3), versioning, and a 7-day lifecycle rule for incomplete multipart uploads.

**Connected to:** ECS file-service tasks (AWS SDK v2 S3Presigner for URL generation), clients (direct PUT/GET via presigned URL).

### Secrets Manager
Stores the RDS master credentials (username + password) as a JSON secret at `/portfolio/dev/rds/master-credentials`. ECS task definitions reference the secret ARN directly; AWS injects the values as environment variables at task launch. Application code never handles credentials in plaintext.

**Connected to:** ECS task definitions (secrets injection), RDS (the credentials it stores).

---

## Messaging

### SNS (Simple Notification Service)
A pub/sub topic (`orders-events`) that `order-service` publishes to when an order is created. Acts as a fan-out point — multiple subscribers can receive the same event without the publisher knowing who they are.

**Connected to:** ECS order-service tasks (publish), SQS (subscriber).

### SQS (Simple Queue Service)
Two queues: `orders-processing` (the main queue) and `orders-processing-dlq` (dead-letter queue). SNS delivers order events to the main queue. The `SqsMessagePoller` inside `order-service` reads from the queue, calls `catalog-service` via gRPC to decrement stock, and marks the order as CONFIRMED or FAILED. Messages that fail processing repeatedly are moved to the DLQ.

**Connected to:** SNS (subscriber), ECS order-service tasks (consumer), CloudWatch (DLQ depth alarm).

---

## Service Discovery

### Cloud Map
Private DNS-based service discovery inside the VPC. Each ECS service registers its task IPs under a DNS name in the `internal.local` namespace (e.g., `catalog-service.internal.local`). `order-service` resolves this name to find `catalog-service` for gRPC calls, without hardcoding IP addresses or using the ALB for internal traffic.

**Connected to:** ECS services (task IP registration), order-service (DNS resolution for gRPC).

---

## Observability

### CloudWatch Logs
Centralized log aggregation. Every ECS task sends its stdout/stderr to a CloudWatch Log Group (one per service). Queryable via CloudWatch Insights. No log agents or sidecar needed — Fargate's `awslogs` log driver handles delivery.

**Connected to:** ECS tasks (log driver), CloudWatch Alarms (log-based metrics, if configured).

### CloudWatch Metrics
Time-series metrics from multiple sources: ALB (request count, latency, 5xx rate), ECS (CPU, memory utilisation), RDS (connections, CPU), SQS (visible message count, oldest message age, DLQ depth), DynamoDB (RCU/WCU). Application services also push custom business metrics (users.created.total, orders.created.total, etc.) via Micrometer's CloudWatch exporter at 60-second intervals.

**Connected to:** CloudWatch Dashboard (visualisation), CloudWatch Alarms (alerting), Application Auto Scaling (scaling signals).

### CloudWatch Dashboard
A single dashboard with 10 widgets covering all five services: ALB requests and latency, ECS CPU and memory per service, RDS connections and CPU, SQS visible/age metrics, DynamoDB RCU/WCU. Gives a full system overview in one view.

**Connected to:** CloudWatch Metrics.

### CloudWatch Alarms
9 alarms covering: 5xx error rate per service (4 alarms), ECS running task count below minimum per service (4 alarms), SQS oldest message age, DLQ depth, RDS CPU. All alarms publish to an SNS topic that emails `dpttraykov@gmail.com`.

**Connected to:** CloudWatch Metrics (signal source), SNS alarm topic (notification delivery).

### X-Ray (via ADOT sidecar)
Distributed tracing across all five services. Each ECS task runs an AWS Distro for OpenTelemetry (ADOT) sidecar container alongside the application. The OTel Java agent (2.7.0) instruments Spring Boot automatically — HTTP requests, gRPC calls, JDBC queries, and SQS message processing all generate trace spans. Traces are visible in the X-Ray console and AWS Service Map.

**Connected to:** ECS tasks (sidecar + Java agent), X-Ray service (trace ingestion).

---

## Security and Identity

### IAM (Identity and Access Management)
Controls what every AWS principal (ECS task, CI runner, Lambda) is allowed to do. Key roles:
- **ECS task execution role** — allows ECS to pull images from ECR and read secrets from Secrets Manager.
- **ECS task role (per service)** — grants each service only the permissions it needs: order-service gets SNS publish + SQS receive; catalog-service gets DynamoDB read/write + ElastiCache; file-service gets S3 presign; all get CloudWatch metrics write and X-Ray send.
- **`portfolio-dev-ci` role** — assumed by GitHub Actions via OIDC; currently AdministratorAccess (flagged for least-privilege hardening).

### IAM OIDC Provider (GitHub Actions)
Allows GitHub Actions to assume an AWS IAM role without storing long-lived AWS credentials as GitHub secrets. GitHub's OIDC token is presented to AWS STS, which validates it against the registered OIDC provider and returns short-lived credentials. Scoped to the specific GitHub repository.

**Connected to:** IAM role (`portfolio-dev-ci`), GitHub Actions workflows.

---

## How It All Fits Together

```
Internet
  └── WAF
        └── API Gateway (JWT auth via Cognito)
              └── VPC Link
                    └── ALB (path-based routing)
                          ├── user-service (ECS Fargate)    ──► RDS PostgreSQL
                          ├── catalog-service (ECS Fargate) ──► DynamoDB + Redis
                          ├── order-service (ECS Fargate)   ──► RDS PostgreSQL
                          │     ├── publishes ──► SNS ──► SQS ──► (self-consumes)
                          │     └── gRPC ─────────────────────────► catalog-service
                          │                                          (via Cloud Map DNS)
                          └── file-service (ECS Fargate)    ──► S3 (presigned URLs)

All ECS tasks:
  ├── pull images from ECR
  ├── read credentials from Secrets Manager
  ├── send logs to CloudWatch Logs
  ├── push metrics to CloudWatch Metrics
  └── send traces to X-Ray (via ADOT sidecar)

CI (GitHub Actions):
  ├── authenticates via OIDC ──► IAM role
  ├── builds + pushes images ──► ECR
  └── deploys ──► ECS
```

---

## System Architecture

### 1. Inbound Traffic Flow

```
  Client / Browser
        │
        ▼
  ┌───────────┐
  │    WAF    │  blocks OWASP Top 10, known bad inputs
  └─────┬─────┘
        │
        ▼
  ┌─────────────────────────────────┐
  │         API Gateway             │◄── Cognito (validates JWT on every request)
  │         (HTTP API)              │
  │  throttle: 500 RPS / 1000 burst │
  └─────────────┬───────────────────┘
                │ VPC Link (private tunnel into VPC)
                │
┌───────────────▼───────────────────────────────────────────────────────────────┐
│ VPC  10.0.0.0/16                                                              │
│                                                                               │
│  ┌─── Public Subnets (eu-west-1a / 1b) ─────────────────────────────────┐     │
│  │                                                                      │     │
│  │   ┌────────────────────────────────────────────────────────────────┐ │     │
│  │   │                  ALB  (internal, path-based routing)           │ │     │
│  │   │   /users/*   /catalog/*   /orders/*   /files/*                 │ │     │
│  │   └──────┬──────────────┬──────────────┬─────────────┬─────────────┘ │     │
│  │          │              │              │             │               │     │
│  │   Internet GW      NAT Gateway                                       │     │ 
│  └──────────────────────────────────────────────────────────────────────┘     │
│                 │              │              │             │                 │
│  ┌─── Private Subnets ─────────────────────────────────────────────────────┐  │
│  │              │              │              │             │              │  │
│  │   ┌──────────▼──┐  ┌────────▼────┐  ┌────▼────────┐  ┌▼────────────┐    │  │
│  │   │ user-service│  │catalog-svc  │  │order-service│  │ file-service│    │  │
│  │   │  :8080      │  │  :8080      │  │  :8080      │  │  :8080      │    │  │
│  │   │  [Fargate]  │  │  [Fargate]  │  │  [Fargate]  │  │  [Fargate]  │    │  │
│  │   └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘    │  │
│  │          │                │                 │                │          │  │
│  │          ▼                ├──► DynamoDB     ▼               ▼           │  │
│  │         RDS               └──► Redis       RDS              S3          │  │
│  │       (Postgres)                         (Postgres)    (presigned URL   │  │
│  │      user-service                       order-service   back to client) │  │
│  └─────────────────────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────────────────┘
```

---

### 2. Event-Driven & Internal Service Communication

```
  order-service
       │
       │  1. POST /orders received
       │  2. Save order to RDS (status: PENDING)
       │  3. Publish event
       ▼
  ┌─────────┐
  │   SNS   │  topic: orders-events
  └────┬────┘
       │ fan-out
       ▼
  ┌─────────┐
  │   SQS   │  queue: orders-processing
  └────┬────┘         (DLQ: orders-processing-dlq)
       │ polled by SqsMessagePoller (same order-service)
       ▼
  order-service
       │
       │  4. gRPC DecrementStock call
       │     (resolves catalog-service.internal.local via Cloud Map DNS)
       ▼
  ┌──────────────────┐
  │  catalog-service │  :9090 (gRPC server)
  │  [Cloud Map DNS] │
  └──────────────────┘
       │
       │  5. Decrement stock in DynamoDB
       │  6. Return OK / INSUFFICIENT_STOCK
       ▼
  order-service
       │
       │  7. Update order status → CONFIRMED or FAILED
       ▼
      RDS
```

---

### 3. Observability

```
  ┌─────────────────────────────────────────────────────────────────┐
  │  Every ECS task runs two containers:                            │
  │                                                                 │
  │   ┌──────────────────────────┐   ┌─────────────────────────┐    │
  │   │   Spring Boot app        │   │   ADOT sidecar          │    │
  │   │   + OTel Java agent      │──►│   (OpenTelemetry        │    │
  │   │                          │   │    Collector)           │    │
  │   └──────────────────────────┘   └──────────┬──────────────┘    │
  └──────────────────────────────────────────────┼──────────────────┘
                                                 │
                    ┌────────────────────────────┼────────────────────────┐
                    │                            │                        │
                    ▼                            ▼                        ▼
           CloudWatch Logs               AWS X-Ray                CloudWatch Metrics
           (stdout / stderr)          (distributed traces,        (custom: users.created,
           one log group              service map across          orders.created, etc.
           per service                all 4 services)             + ALB / ECS / RDS /
                                                                  SQS / DynamoDB metrics)
                                                                         │
                                                                         ▼
                                                                 CloudWatch Dashboard
                                                                 (10 widgets, full view)
                                                                         │
                                                                         ▼
                                                                 CloudWatch Alarms (9)
                                                                         │
                                                                         ▼
                                                                  SNS alarm topic
                                                                         │
                                                                         ▼
                                                                   Email alert
```

---

### 4. CI/CD Pipeline

```
  Developer
      │
      │  git push → main
      ▼
  GitHub Actions (ci.yml)
      │
      │  1. Authenticate (no stored AWS credentials)
      ▼
  ┌─────────────────────┐
  │  GitHub OIDC token  │──► AWS STS ──► IAM role (portfolio-dev-ci)
  └─────────────────────┘               short-lived credentials
                                               │
                          ┌────────────────────┼────────────────────┐
                          │                    │                    │
                          ▼                    ▼                    ▼
                   ./mvnw verify          docker build         ecs update-service
                   (unit + IT tests)      + push image    (rolling deploy, or
                                          ──► ECR         CodeDeploy blue/green)
                                         (only changed
                                          services via
                                          git-diff detection)
```

---

### 5. Security Boundaries

```
  ┌─── What can reach what ──────────────────────────────────────────────────┐
  │                                                                          │
  │  Internet          → WAF → API Gateway only (no direct VPC access)       │
  │  API Gateway       → ALB only (via VPC Link, private)                    │
  │  ALB               → ECS tasks only (security group rule)                │
  │  ECS tasks         → their own data store + Secrets Manager + AWS APIs   │
  │                       (outbound via NAT Gateway)                         │
  │  ECS user-service  → RDS only                                            │
  │  ECS catalog-svc   → DynamoDB + Redis only                               │
  │  ECS order-service → RDS + SNS + SQS + catalog-service (gRPC)            │
  │  ECS file-service  → S3 only                                             │
  │  RDS               → ECS tasks only (port 5432, no public access)        │
  │  Redis             → ECS catalog-service only (port 6379)                │
  │  S3                → ECS file-service (presign) + clients (direct PUT/   │
  │                       GET via presigned URL, time-limited)               │
  │                                                                          │
  │  IAM task roles are scoped per service — no service can access           │
  │  another service's data store.                                           │
  └──────────────────────────────────────────────────────────────────────────┘
```
