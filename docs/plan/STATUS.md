# Project Status

## Current Phase
Phase 1 — Networking + ECR

## Summary
Phase 0 is complete. The Maven multi-module monorepo is set up with `user-service` running locally. `./mvnw verify` passes with integration tests.

## Completed Phases
- Phase 0 — Local Foundation: Maven monorepo, `user-service`, Flyway, Testcontainers IT tests passing.

## Notes
- Spring Boot 4.0.6 workarounds documented in `CLAUDE.md` — apply to every service module.
- OpenTofu must be installed before Phase 1 (`asdf plugin add opentofu && asdf install opentofu latest`).
- Single NAT Gateway chosen over VPC endpoints (cost vs. simplicity tradeoff, documented in CLAUDE.md).
