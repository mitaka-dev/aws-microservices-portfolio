# Project Status

## Current Phase
Phase 3 — First service on Fargate behind ALB

## Summary
Phase 2 is complete. Cognito User Pool + public app client provisioned. HTTP API Gateway with JWT authorizer deployed. Verified: curl without token → 401, curl with valid Cognito ID token → 200.

## Completed Phases
- Phase 0 — Local Foundation: Maven monorepo, `user-service`, Flyway, Testcontainers IT tests passing.
- Phase 1 — Networking + ECR: VPC (10.0.0.0/16), 2 public + 2 private subnets, single NAT Gateway, 4 ECR repos (`portfolio-dev-{user,catalog,order,file}-service`). State in S3 (`portfolio-tfstate-476114152732`).
- Phase 2 — Cognito + API Gateway: User Pool (`eu-west-1_ygB44lFai`), public app client, Hosted UI domain. HTTP API (`qqczpb3x2h`), JWT authorizer, `/health` route backed by inline Lambda. `scripts/get-token.sh` for test tokens.

## Notes
- Spring Boot 4.0.6 workarounds documented in `CLAUDE.md` — apply to every service module.
- AWS profile: `aws-microservices-portfolio` (eu-west-1, account 476114152732).
- Single NAT Gateway chosen over VPC endpoints (cost vs. simplicity tradeoff, documented in CLAUDE.md).
- Run `tofu destroy` from `infra/envs/dev/` when not developing to avoid charges (NAT GW + EIP are the main cost drivers at ~$35/month each).
- Cognito issuer URI: `https://cognito-idp.eu-west-1.amazonaws.com/eu-west-1_ygB44lFai` — used in Phase 3 Spring Security config.
