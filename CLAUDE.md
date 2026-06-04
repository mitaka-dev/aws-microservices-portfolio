# AWS Microservices Portfolio

## Project

Five-service AWS portfolio: `user-service`, `catalog-service`, `order-service`, `file-service`, `payment-service`.
Stack: Java 25, Spring Boot 4.0.6, Maven multi-module monorepo, ECS Fargate, OpenTofu, PostgreSQL/DynamoDB, Redis, SNS, gRPC, Cognito JWT.

## Rules
- **Never run `tofu apply`** without showing `tofu plan` output first and getting approval.
- Never commit `.tfstate`, `.env`, or AWS credentials. Remote state in S3 only.
- Pin all OpenTofu provider/module versions (`required_version`, `required_providers`).

## Commands

```bash
# Build and test (from repo root)
./mvnw verify                                # full build + IT tests
./mvnw -pl user-service -am verify           # single-service build + IT
./mvnw -DskipTests package                   # skip tests
./mvnw -pl proto-shared clean install        # rebuild proto stubs only

# Local dev
docker compose up -d                         # start Postgres + Redis
./mvnw -pl user-service spring-boot:run -Dspring-boot.run.profiles=local
```

## Module Status

All 5 service modules are fully implemented:
- `proto-shared` — shared gRPC stubs (`catalog.proto`, `payment.proto`, `user.proto`)
- `user-service` — REST, PostgreSQL, Cognito JWT, Flyway
- `catalog-service` — REST + gRPC server, DynamoDB, Redis cache
- `order-service` — REST, PostgreSQL, gRPC clients (catalog + payment), SNS publish, outbox (Phase 9)
- `file-service` — REST, S3 presigned URLs
- `payment-service` — gRPC server only (no ALB), Strategy pattern, PostgreSQL. Autoscales on CPU only (no ALB target group = no ALBRequestCountPerTarget). Revisit if load tests show it as a bottleneck.

## Infrastructure Source of Truth

**OpenTofu is the primary IaC implementation.** `infra/` is the real infrastructure — all changes go here first.

`infra-pulumi/` is a Pulumi Java port that mirrors OpenTofu exactly. 
**Never change Pulumi first.** The workflow is:
1. Design and implement the change in `infra/` (OpenTofu)
2. Run `tofu plan` / `tofu apply` to validate
3. Port the equivalent change to `infra-pulumi/` (Pulumi Java)

If OpenTofu and Pulumi diverge, OpenTofu wins.

## Networking

Private subnets route via a **single NAT Gateway** in one public subnet. Do NOT create VPC interface endpoints for ECR, Secrets Manager, CloudWatch Logs, etc. — NAT was chosen deliberately for simplicity.

# Standard workflow (from infra/envs/dev/)
tofu init
tofu plan          # always review before applying
tofu apply         # requires explicit user approval — never auto-apply
tofu destroy       # tear down to avoid charges when not in use

## Spring Boot 4.0.6 Workarounds

Apply all of these to every service module. These were discovered during Phase 0 building `user-service`.

### 1. Flyway — add `spring-boot-flyway` module

Flyway autoconfiguration moved out of `spring-boot-autoconfigure` into a separate module in SB4. 

### 2. Spring Framework 7 — `-parameters` compiler flag required

`@PathVariable`, `@RequestParam`, etc. no longer infer parameter names from bytecode. 
Already set in parent pom `pluginManagement`.

### 3. `@AutoConfigureMockMvc` — add `spring-boot-starter-webmvc-test` module

The annotation moved to `org.springframework.boot.webmvc.test.autoconfigure` in SB4. Add the module and use it normally — no manual `@BeforeEach` setup needed.

### 4. Failsafe forked JVM — Spring Framework 7 annotation traversal fails

`TestContextAnnotationUtils.findAnnotationDescriptor` fails to traverse `@BootstrapWith` meta-annotation when Failsafe forks a new JVM after `spring-boot:repackage` puts the fat JAR on the classpath. Two-part fix (already applied globally).

- `spring-boot-maven-plugin` with `<classifier>exec</classifier>` — keeps thin JAR as the main artifact so Failsafe sees plain classes, not `BOOT-INF/classes/`
- `.mvn/maven.config` contains `-Dfailsafe.forkCount=0` — runs IT tests in Maven's JVM, bypassing the forked classloader entirely

### 5. `ddl-auto: none` — not `validate`

Flyway owns the schema; Hibernate must not validate it. 
With SB4's changed initialization ordering, `validate` causes context startup failures even with the Flyway fix above. Use `none` everywhere:

spring.jpa.hibernate.ddl-auto: none

### 6. `TestRestTemplate` — add `spring-boot-resttestclient` module

`TestRestTemplate` moved to `org.springframework.boot.resttestclient`. Use `@AutoConfigureTestRestTemplate` and `WebEnvironment.RANDOM_PORT`

### 7. `ObjectMapper` — add `spring-boot-jackson2` module

Jackson 2 autoconfiguration moved to a separate `spring-boot-jackson2` module in SB4. 
Add it (test scope if only needed in tests) to get `ObjectMapper` auto-registered as a bean.


### 8. Use Spring Boot 4.0.6, not 4.0.3

SB 4.0.3 used Spring Framework 7.0.5 which had a bug where `@BootstrapWith` 
traversal always failed even in-process. SB 4.0.6 (Spring Framework 7.0.7) 
partially fixes it — still needs the two-part forked-JVM fix above.

### 9. Dockerfile — copy the exec jar, not `*.jar`

The `spring-boot-maven-plugin` with `<classifier>exec</classifier>` produces two jars: `*-SNAPSHOT.jar` (thin) and `*-SNAPSHOT-exec.jar` (fat). A `*.jar` glob in `COPY --from=builder` matches both and fails. Always use:

COPY --from=builder /build/<service>/target/*-exec.jar app.jar

### 10. `SqsAutoConfiguration` — exclude and wire manually

`SqsAutoConfiguration` (spring-cloud-aws 3.4.0) calls `PropertyMapper.alwaysApplyingWhenNonNull()` which was removed in SB4. Fix:

1. Exclude the autoconfiguration in `application.yml`:
```yaml
spring.autoconfigure.exclude:
  - io.awspring.cloud.autoconfigure.sqs.SqsAutoConfiguration
```
2. Provide `SqsAsyncClient` bean manually in a `SqsConfig` class (wire from `spring.cloud.aws.*` properties).
3. Replace `@SqsListener` with a manual `SmartLifecycle` poller (`SqsMessagePoller`) that calls `SqsAsyncClient.receiveMessage()` in a loop.

`SnsAutoConfiguration` does NOT have this issue — `SnsTemplate` autoconfigures fine.

> **Note:** The SQS consumer was removed from `order-service` in Phase 8. This workaround applies to any service that re-introduces `SqsAsyncClient` (e.g. Phase 9 saga recovery job if it consumes from SQS).

### 11. `CloudWatchExportAutoConfiguration` — exclude in all services

`CloudWatchExportAutoConfiguration` (spring-cloud-aws 3.4.0) references `StepRegistryProperties` which was removed in SB4. Any service with spring-cloud-aws on the classpath will fail to start unless this is excluded. Add to the top-level `spring:` block of every `application.yml`:

```yaml
spring:
  autoconfigure:
    exclude:
      - io.awspring.cloud.autoconfigure.metrics.CloudWatchExportAutoConfiguration
```

### 12. OTel Java agent — use `apt-get install curl` in Dockerfile, not `ADD https://`

`eclipse-temurin:25-jdk` has neither `curl` nor `wget`. Docker's `ADD https://` for GitHub release URLs silently downloads an HTML redirect page instead of the JAR. Use:

```dockerfile
FROM eclipse-temurin:25-jdk AS otel-agent
ARG OTEL_AGENT_VERSION=2.7.0
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir /otel \
    && curl -fsSL -o /otel/javaagent.jar \
       "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar"
```

OTel agent 2.7.0 logs a non-fatal `InaccessibleObjectException` on Java 25 (module system restriction) but the JVM and Spring Boot continue normally.