# Project Status

## Current Phase
Phase 2 — Cognito + API Gateway (auth at the edge)

## Summary
Phase 1 is complete. VPC, subnets, NAT Gateway, and 4 ECR repositories are provisioned in eu-west-1 via OpenTofu. user-service image has been built and pushed to ECR (tag: 7336b89).

## Completed Phases
- Phase 0 — Local Foundation: Maven monorepo, `user-service`, Flyway, Testcontainers IT tests passing.
- Phase 1 — Networking + ECR: VPC (10.0.0.0/16), 2 public + 2 private subnets, single NAT Gateway, 4 ECR repos (`portfolio-dev-{user,catalog,order,file}-service`). State in S3 (`portfolio-tfstate-476114152732`).

## Notes
- Spring Boot 4.0.6 workarounds documented in `CLAUDE.md` — apply to every service module.
- AWS profile: `aws-microservices-portfolio` (eu-west-1, account 476114152732).
- Single NAT Gateway chosen over VPC endpoints (cost vs. simplicity tradeoff, documented in CLAUDE.md).
- Run `tofu destroy` from `infra/envs/dev/` when not developing to avoid charges (NAT GW + EIP are the main cost drivers at ~$35/month each).
