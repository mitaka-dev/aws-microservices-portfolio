# Project Status

## Current Phase
Phase 4 — Remaining services

## Summary
Phase 3 is complete. `user-service` deployed to ECS Fargate (`portfolio-dev-cluster`), behind internal ALB, behind API Gateway HTTP API with Cognito JWT authorizer. Flyway migration ran on startup. Verified: 401 without token, 201 POST /users, 200 GET /users/1. CloudWatch Logs confirm requests reached the container.

## Completed Phases
- Phase 0 — Local Foundation: Maven monorepo, `user-service`, Flyway, Testcontainers IT tests passing.
- Phase 1 — Networking + ECR: VPC (10.0.0.0/16), 2 public + 2 private subnets, single NAT Gateway, 4 ECR repos (`portfolio-dev-{user,catalog,order,file}-service`). State in S3 (`portfolio-tfstate-476114152732`).
- Phase 2 — Cognito + API Gateway: User Pool, public app client, Hosted UI domain. HTTP API, JWT authorizer, `/health` route backed by inline Lambda.
- Phase 3 — First service on Fargate behind ALB: Internal ALB (`portfolio-dev-alb`), RDS PostgreSQL `db.t4g.micro` (`portfolio-dev-postgres`), ECS cluster (`portfolio-dev-cluster`), `user-service` on Fargate. DB credentials via ECS secrets injection from Secrets Manager (`/portfolio/dev/rds/master-credentials`). Full path: API GW → VPC Link → ALB → Fargate → RDS.

## Notes
- Spring Boot 4.0.6 workarounds documented in `CLAUDE.md` — apply to every service module.
- AWS profile: `aws-microservices-portfolio` (eu-west-1, account 476114152732).
- Single NAT Gateway chosen over VPC endpoints (cost vs. simplicity tradeoff, documented in CLAUDE.md).
- Run `tofu destroy` from `infra/envs/dev/` when not developing to avoid charges (NAT GW + EIP + RDS are the main cost drivers).
- API Gateway endpoint trailing slash: when using `tofu output -raw api_gateway_endpoint`, strip the trailing slash before appending paths: `BASE="${API%/}"` then `"${BASE}/users"`.
- Spring Boot startup time on Fargate 256 CPU / 512 MB: ~107 seconds. ALB health check `start_period` may need extending if tasks fail health checks before startup completes.
