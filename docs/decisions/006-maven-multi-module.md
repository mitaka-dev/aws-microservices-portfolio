# ADR 006 — Maven Multi-Module with Parent POM as BOM

## Context

The monorepo contains five Maven modules: `proto-shared`, `user-service`, `catalog-service`, `order-service`, `file-service`. All services share a technology stack (Spring Boot 4, Spring Cloud AWS, AWS SDK v2, gRPC, Testcontainers) and need consistent dependency versions. Build tooling must support running integration tests with Testcontainers, producing executable JARs for Docker, and enabling per-service builds in CI.

## Decision

Use a **Maven multi-module project** with a parent POM that acts as a Bill of Materials (BOM). The parent POM declares:

- `<dependencyManagement>` importing Spring Boot BOM, Spring Cloud AWS BOM, AWS SDK v2 BOM, and gRPC BOM — all version-pinned.
- `<pluginManagement>` configuring the Maven Compiler Plugin (`-parameters` flag), Failsafe Plugin (`forkCount=0`, see CLAUDE.md), and Spring Boot Maven Plugin (`<classifier>exec</classifier>`).
- No `<dependencies>` — modules declare only what they actually use.

The reactor build order is inferred from `<modules>` declarations and inter-module `<dependency>` references.

## Consequences

**Positive:**
- A single version upgrade in the parent POM propagates to all modules. Upgrading Spring Boot 4.0.3 → 4.0.6 required editing one line.
- The `spring-boot-maven-plugin` with `<classifier>exec</classifier>` produces two JARs per service: a thin JAR (primary artifact, used by Failsafe) and a fat `*-exec.jar` (used by Docker). This cleanly separates integration testing from containerisation.
- `./mvnw -pl order-service -am verify` builds only `proto-shared` and `order-service` — the `-am` flag automatically resolves the upstream module graph.
- Testcontainers JVM reuse (`testcontainers.reuse.enable=true`) works across modules because all tests run in the same Maven JVM (Failsafe `forkCount=0`).

**Negative:**
- Maven reactor does not do true incremental builds — `./mvnw verify` always recompiles every module. For large monorepos this is slow; at five modules it is acceptable.
- The `<classifier>exec</classifier>` Dockerfile pattern requires explicitly copying `*-exec.jar` instead of `*.jar` — a non-obvious convention documented in CLAUDE.md.
- Adding a new Maven module requires updating all sibling Dockerfiles to COPY the new module's `pom.xml` so the Maven reactor can resolve it during the Docker layer-caching dependency step.

## Alternatives Considered

**Gradle multi-project with composite builds:** Gradle's build cache and configuration cache significantly reduce rebuild time for large projects. The incremental task model is more powerful than Maven's. However, the Spring Boot and Spring Cloud AWS ecosystems publish Maven-centric documentation, and the tooling (Spring Initializr, most tutorials) defaults to Maven. For a portfolio demonstrating Spring Boot 4 patterns, Maven reduces friction.

**Flat structure with independent POMs per service:** Each service manages its own dependencies. Eliminates the parent POM complexity but loses centralised version management — version drift across services becomes a real risk as the project grows.
