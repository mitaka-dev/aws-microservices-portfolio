# ADR 005 — Monorepo Structure

## Context

The portfolio contains five services (`user-service`, `catalog-service`, `order-service`, `payment-service`, `file-service`) plus two proto modules (`proto-catalog`, `proto-payment`) that contain Protobuf definitions and generated gRPC stubs. `proto-catalog` is used by `catalog-service` and `order-service`; `proto-payment` is used by `payment-service` and `order-service`. Infrastructure, tests, and scripts are also part of the project.

The structural choice was: a single repository (monorepo) or one repository per service (polyrepo).

## Decision

Use a **single Maven multi-module monorepo** for all services, shared modules, infrastructure, and tests.

## Consequences

**Positive:**
- `proto-catalog` and `proto-payment` are first-class Maven modules. Each service declares a dependency only on the proto artifact it uses — no publishing to a Maven registry, no version pinning across repos, and a proto change in one service does not force recompilation of unrelated services.
- Cross-service changes are atomic. When a Protobuf contract changes, the proto stubs, the gRPC server, and the gRPC client all update in one commit. There is no multi-repo coordination problem.
- A single CI pipeline validates the whole system. The GitHub Actions `ci.yml` workflow uses git-diff detection to build and deploy only the changed service(s), so the blast radius of a build is bounded in practice.
- Infrastructure and application code are co-located. An ECS task definition change and the application change that requires it land in the same PR.

**Negative:**
- Any commit triggers the CI pipeline against the full repository, even if the change is a README edit. Mitigated by git-diff service detection in the CI workflow.
- A breaking change in the parent POM or shared configuration affects all services simultaneously. There is no incremental rollout of framework upgrades.
- A single `CODEOWNERS` file cannot enforce per-team ownership without path-based rules. As the number of teams grows, coordination overhead increases.
- Repository checkout time grows with history. At portfolio scale this is negligible; at thousands of commits with large assets it becomes meaningful.

## Alternatives Considered

**Polyrepo (one repo per service):** Each service is independently versioned, independently deployable, and owned by a separate team without risk of stepping on each other. `proto-catalog` and `proto-payment` would each need their own repo and versioned artifacts in a Maven registry. Cross-service proto changes require coordinated PRs across at least three repos. The right model once different teams own different services; coordination overhead is the price of autonomy.

**Monorepo with Gradle:** Gradle's incremental build and build cache are stronger than Maven's for large monorepos. The tradeoff is a steeper learning curve and less mature Spring Boot tooling. Maven was chosen because Spring Boot's official tooling, documentation, and community examples all use Maven, reducing friction for a portfolio built around Spring Boot 4.
