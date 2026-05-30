# Project Status

## Current Phase
Phase 4c — file-service

## Summary
Phase 4a is complete. `catalog-service` deployed to ECS Fargate, behind internal ALB, behind API Gateway. DynamoDB single-table design, Redis cache-aside (5-min TTL), gRPC server on port 9090, Cloud Map registration (`catalog-service.internal.local`). Verified: 401 without token, 201 POST /catalog, 200 GET /catalog/{id}, 200 GET /catalog.

## Completed Phases
- Phase 0 — Local Foundation: Maven monorepo, `user-service`, Flyway, Testcontainers IT tests passing.
- Phase 1 — Networking + ECR: VPC (10.0.0.0/16), 2 public + 2 private subnets, single NAT Gateway, 4 ECR repos (`portfolio-dev-{user,catalog,order,file}-service`). State in S3 (`portfolio-tfstate-476114152732`).
- Phase 2 — Cognito + API Gateway: User Pool, public app client, Hosted UI domain. HTTP API, JWT authorizer, `/health` route backed by inline Lambda.
- Phase 3 — First service on Fargate behind ALB: Internal ALB (`portfolio-dev-alb`), RDS PostgreSQL `db.t4g.micro` (`portfolio-dev-postgres`), ECS cluster (`portfolio-dev-cluster`), `user-service` on Fargate. DB credentials via ECS secrets injection from Secrets Manager (`/portfolio/dev/rds/master-credentials`). Full path: API GW → VPC Link → ALB → Fargate → RDS.
- Phase 4a — catalog-service: DynamoDB table `portfolio-dev-catalog` (single-table, on-demand), ElastiCache Redis `cache.t4g.micro`, Cloud Map namespace `internal.local`, catalog-service on Fargate with gRPC server on port 9090. Full path: API GW → VPC Link → ALB → Fargate → DynamoDB/Redis.
- Phase 4b — order-service: PostgreSQL persistence, SNS topic `orders-events`, SQS queue `orders-processing` (+ DLQ), manual `SqsMessagePoller` SmartLifecycle (SqsAutoConfiguration excluded — SB4 compat), gRPC client to catalog-service via Cloud Map DNS. Flow: POST /orders → save → SNS publish → SQS consume → gRPC DecrementStock → CONFIRMED/FAILED. tofu plan shows 117 resources (not yet applied — pending file-service).

## Notes
- Spring Boot 4.0.6 workarounds documented in `CLAUDE.md` — apply to every service module.
- AWS profile: `aws-microservices-portfolio` (eu-west-1, account 476114152732).
- Single NAT Gateway chosen over VPC endpoints (cost vs. simplicity tradeoff, documented in CLAUDE.md).
- Run `tofu destroy` from `infra/envs/dev/` when not developing to avoid charges (NAT GW + EIP + RDS + ElastiCache are the main cost drivers).
- API Gateway endpoint trailing slash: when using `tofu output -raw api_gateway_endpoint`, strip the trailing slash before appending paths: `BASE="${API%/}"` then `"${BASE}/catalog"`.
- Spring Boot startup time on Fargate 256 CPU / 512 MB: ~107 seconds. `health_check_grace_period_seconds = 120` is now set on all ECS services.
- DynamoDB table name is environment-specific (`portfolio-dev-catalog`). Passed as `DYNAMODB_TABLE_NAME` env var; Spring binds it via `${dynamodb.table.name:catalog}`. Local profile / tests use `catalog`.
- Dockerfile layer caching for services with local module dependencies: install root POM with `-N install`, then install proto-shared, then `dependency:resolve` for the service. See catalog-service/Dockerfile.
- When adding a new Maven module to the parent pom, update all sibling Dockerfiles to also COPY the new module's pom.xml (so Maven reactor can find it during the dependency layer step).
- `SqsAutoConfiguration` (SCA 3.4.0) is incompatible with SB4 — exclude it and use manual `SqsMessagePoller` SmartLifecycle + manual `SqsAsyncClient` bean. `SnsAutoConfiguration` is fine. See CLAUDE.md workaround #10.
