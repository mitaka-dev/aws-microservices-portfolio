---
name: monorepo-maven-conventions
description: BOM, parent POM, and module conventions for this Maven monorepo. Use when adding dependencies, creating a new module, or editing any pom.xml.
allowed-tools: Read, Edit, Write
---

# Monorepo Maven Conventions

## Golden Rule

**BOM owns all dependency versions. Service POMs never declare `<version>` inside `<dependency>`.**

If you add a dependency to a service POM and it doesn't have a version, that's correct.
If it does have a version, remove it and add the version management to the root `pom.xml` `<dependencyManagement>` block instead.

## Structure

```
aws-microservices-portfolio (root pom.xml, groupId: com.portfolio)
├── proto-shared                — gRPC protobuf definitions + generated Java stubs (no port)
├── user-service        :8080  — PostgreSQL (userdb)
├── catalog-service     :8081  — PostgreSQL (catalogdb) + gRPC :9090
├── order-service       :8082  — DynamoDB
└── file-service        :8083  — S3 + SQS
```

## Root pom.xml Key Properties

```xml
<properties>
    <java.version>25</java.version>
    <spring-boot.version>4.0.6</spring-boot.version>
    <spring-cloud-aws.version>3.x.x</spring-cloud-aws.version>
    <aws-java-sdk.version>2.x.x</aws-java-sdk.version>
    <grpc.version>1.x.x</grpc.version>
    <springdoc.version>3.x.x</springdoc.version>
</properties>
```

The root POM uses Spring Boot BOM in `<dependencyManagement>` (not as `<parent>`) so the project can own its own parent:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-dependencies</artifactId>
            <version>${spring-boot.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <!-- additional BOMs below -->
    </dependencies>
</dependencyManagement>
```

## Adding a Dependency to a Service

**Step 1** — Add to the service's `pom.xml` (NO version):
```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>some-library</artifactId>
</dependency>
```

**Step 2** — If the dependency is NOT managed by Spring Boot BOM or one of the imported BOMs, add it to root `pom.xml` `<dependencyManagement>`:
```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>some-library</artifactId>
    <version>1.2.3</version>
</dependency>
```

Spring Boot BOM already manages: Spring Data, Spring Security, Spring Web, Micrometer, Jackson, Hibernate, PostgreSQL driver, Flyway (core), Redis client, etc.

**Explicitly managed via imported BOMs in this project's root POM:**
- `software.amazon.awssdk:bom` → `${aws-java-sdk.version}` (AWS SDK v2)
- `io.awspring.cloud:spring-cloud-aws-dependencies` → `${spring-cloud-aws.version}` (Spring Cloud AWS — SQS, S3, Secrets Manager)
- `io.grpc:grpc-bom` → `${grpc.version}` (gRPC stubs)
- `org.springdoc:springdoc-openapi-starter-webmvc-ui` → `${springdoc.version}` (managed in `<dependencyManagement>`, not a BOM import)

## Creating a New Module

1. Create `{service-name}/pom.xml` with parent reference and NO `<version>` in `<project>`:
```xml
<parent>
    <groupId>com.portfolio</groupId>
    <artifactId>aws-microservices-portfolio</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <relativePath>../pom.xml</relativePath>
</parent>
<artifactId>{service-name}</artifactId>
```

2. Add `<module>{service-name}</module>` to root `pom.xml` modules list.

3. `proto-shared` must remain the first `<module>` — it must build before services that depend on its generated gRPC stubs.

4. Add Dockerfile + docker-compose entry (commented out until implemented).

## Spring Boot 4 Module Dependencies

These modules must be added explicitly — they moved out of `spring-boot-autoconfigure` in SB4:

```xml
<!-- Flyway autoconfiguration -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-flyway</artifactId>
</dependency>

<!-- MockMvc autoconfiguration (test scope) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc-test</artifactId>
    <scope>test</scope>
</dependency>

<!-- TestRestTemplate (test scope) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-resttestclient</artifactId>
    <scope>test</scope>
</dependency>

<!-- ObjectMapper bean (test scope if only in tests) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-jackson2</artifactId>
    <scope>test</scope>
</dependency>
```

See CLAUDE.md for the full list of SB4 workarounds.

## Anti-Patterns

| Anti-pattern | Fix |
|---|---|
| `<version>` in service `<dependency>` block | Move to root `<dependencyManagement>` |
| Duplicate entries in `<dependencies>` | Remove one; Spring Boot deduplicates transitively |
| `<parent>` pointing to `spring-boot-starter-parent` in a service POM | Remove — parent is `aws-microservices-portfolio` |
| `<module>` added without a `pom.xml` existing | Create the pom.xml first |
| `proto-shared` not listed first in root `<modules>` | Keep it first — downstream services need its generated sources |

## Verification

```bash
./mvnw dependency:tree -q        # no version conflict warnings
./mvnw compile -q                # all modules compile
```
