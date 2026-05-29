# AWS Microservices Portfolio — Claude Instructions

## Project

Four-service AWS portfolio: `user-service`, `catalog-service`, `order-service`, `file-service`.
Stack: Java 25, Spring Boot 4.0.6, Maven multi-module monorepo, ECS Fargate, OpenTofu, PostgreSQL/DynamoDB, Redis, SQS, gRPC, Cognito JWT.

## Rules

- **Never run `git commit` or `git push`** unless explicitly asked.
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

Only `user-service` and `proto-shared` are real modules. `catalog-service`, `order-service`, and `file-service` contain only `.gitkeep` placeholders — do not create source files in them until their build-plan phase.

## Networking

Private subnets route via a **single NAT Gateway** in one public subnet. Do NOT create VPC interface endpoints for ECR, Secrets Manager, CloudWatch Logs, etc. — NAT was chosen deliberately for simplicity.

## OpenTofu

Remote state uses S3 + DynamoDB. Bootstrap these two resources manually once before the first `tofu init` (they cannot be managed by the state they back):

```bash
# First-time bootstrap only
aws s3 mb s3://<tfstate-bucket> --region eu-west-1
aws dynamodb create-table --table-name tfstate-lock \
  --attribute-definitions AttributeName=LockID,AttributeType=S \
  --key-schema AttributeName=LockID,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST --region us-east-1

# Standard workflow (from infra/envs/dev/)
tofu init
tofu plan          # always review before applying
tofu apply         # requires explicit user approval — never auto-apply
tofu destroy       # tear down to avoid charges when not in use
```

## Spring Boot 4.0.6 Workarounds

Apply all of these to every service module. These were discovered during Phase 0 building `user-service`.

### 1. Flyway — add `spring-boot-flyway` module

Flyway autoconfiguration moved out of `spring-boot-autoconfigure` into a separate module in SB4. Add it explicitly — no manual `FlywayConfig` needed:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-flyway</artifactId>
</dependency>
```

### 2. Spring Framework 7 — `-parameters` compiler flag required

`@PathVariable`, `@RequestParam`, etc. no longer infer parameter names from bytecode. Already set in parent pom `pluginManagement`:

```xml
<plugin>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration><parameters>true</parameters></configuration>
</plugin>
```

### 3. `@AutoConfigureMockMvc` — add `spring-boot-starter-webmvc-test` module

The annotation moved to `org.springframework.boot.webmvc.test.autoconfigure` in SB4. Add the module and use it normally — no manual `@BeforeEach` setup needed:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc-test</artifactId>
    <scope>test</scope>
</dependency>
```

```java
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class MyIT {
    @Autowired MockMvc mockMvc;
}
```

### 4. Failsafe forked JVM — Spring Framework 7 annotation traversal fails

`TestContextAnnotationUtils.findAnnotationDescriptor` fails to traverse `@BootstrapWith` meta-annotation when Failsafe forks a new JVM after `spring-boot:repackage` puts the fat JAR on the classpath. Two-part fix (already applied globally):

- `spring-boot-maven-plugin` with `<classifier>exec</classifier>` — keeps thin JAR as the main artifact so Failsafe sees plain classes, not `BOOT-INF/classes/`
- `.mvn/maven.config` contains `-Dfailsafe.forkCount=0` — runs IT tests in Maven's JVM, bypassing the forked classloader entirely

### 5. `ddl-auto: none` — not `validate`

Flyway owns the schema; Hibernate must not validate it. With SB4's changed initialization ordering, `validate` causes context startup failures even with the Flyway fix above. Use `none` everywhere:

```yaml
spring.jpa.hibernate.ddl-auto: none
```

### 6. `TestRestTemplate` — add `spring-boot-resttestclient` module

`TestRestTemplate` moved to `org.springframework.boot.resttestclient`. Use `@AutoConfigureTestRestTemplate` and `WebEnvironment.RANDOM_PORT`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-resttestclient</artifactId>
    <scope>test</scope>
</dependency>
```

```java
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class MyIT {
    @Autowired TestRestTemplate restTemplate;
}
```

### 7. `ObjectMapper` — add `spring-boot-jackson2` module

Jackson 2 autoconfiguration moved to a separate `spring-boot-jackson2` module in SB4. Add it (test scope if only needed in tests) to get `ObjectMapper` auto-registered as a bean:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-jackson2</artifactId>
    <scope>test</scope>
</dependency>
```

### 8. Use Spring Boot 4.0.6, not 4.0.3

SB 4.0.3 used Spring Framework 7.0.5 which had a bug where `@BootstrapWith` traversal always failed even in-process. SB 4.0.6 (Spring Framework 7.0.7) partially fixes it — still needs the two-part forked-JVM fix above.

### 9. Dockerfile — copy the exec jar, not `*.jar`

The `spring-boot-maven-plugin` with `<classifier>exec</classifier>` produces two jars: `*-SNAPSHOT.jar` (thin) and `*-SNAPSHOT-exec.jar` (fat). A `*.jar` glob in `COPY --from=builder` matches both and fails. Always use:

```dockerfile
COPY --from=builder /build/<service>/target/*-exec.jar app.jar
```
