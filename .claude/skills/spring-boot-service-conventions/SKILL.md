---
name: spring-boot-service-conventions
description: How every Spring Boot service in this monorepo is structured. Use when creating or editing any service — controllers, DTOs, SQS listeners, entities, or application config.
allowed-tools: Read, Edit, Write, Bash(./mvnw *)
---

# Spring Boot Service Conventions

This project runs **Java 25 + Spring Boot 4.0.6 + Spring Framework 7 + Servlet 6.1 (Jakarta EE 11)**.

## Package Layout

Every service follows this structure under `com.portfolio.{service}`:

```
{service}-service/src/main/java/com/portfolio/{service}/
├── {Service}Application.java       ← @SpringBootApplication main class
├── controller/                     ← @RestController + request/response records
├── service/                        ← Business logic, @Transactional methods
├── domain/                         ← @Entity classes + JpaRepository interfaces (PostgreSQL services)
├── dto/                            ← DTOs (Java records — immutable)
├── config/                         ← @Configuration classes (SecurityConfig, OpenApiConfig, etc.)
└── exception/                      ← Domain exceptions + GlobalExceptionHandler
```

`order-service` additionally has:
```
├── grpc/                           ← gRPC stubs + CatalogServiceGrpcClient
```

## Controller Conventions

```java
@RestController
@RequestMapping("/api/v1/{resources}")   // kebab-case, plural, always /api/v1/ prefix
public class OrderController {

    private final OrderService orderService;

    // Constructor injection — NEVER @Autowired on fields
    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderDto> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody @Valid CreateOrderRequest request) {
        String userId = jwt.getSubject();       // Cognito sub (UUID)
        String email  = jwt.getClaimAsString("email");
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.create(userId, request));
    }
}
```

- DTOs are **Java records** (`public record CreateOrderRequest(@NotBlank String item) {}`)
- Use `ResponseEntity<T>` when status code varies; plain `T` for always-200 reads
- Identity comes from the validated Cognito JWT — extract via `@AuthenticationPrincipal Jwt jwt`, never parse the token manually
- `@Valid` on every `@RequestBody` — never skip it

## Service Layer

```java
@Service
public class OrderService {

    private final OrderRepository repository;
    private final SqsTemplate sqsTemplate;

    @Value("${aws.sqs.order-events-queue-url}")
    private String orderEventsQueueUrl;

    public OrderService(OrderRepository repository, SqsTemplate sqsTemplate) {
        this.repository = repository;
        this.sqsTemplate = sqsTemplate;
    }

    @Transactional
    public OrderDto create(String userId, CreateOrderRequest request) {
        Order order = new Order();
        // ... populate
        repository.save(order);
        sqsTemplate.send(to -> to.queue(orderEventsQueueUrl).payload(new OrderCreatedEvent(order.getId(), userId)));
        return toDto(order);
    }
}
```

- All state-changing methods are `@Transactional`
- SQS publish and DB save happen in the same method (see `/outbox-pattern` for reliability guarantees)

## Domain / Entity

```java
@Entity
@Table(name = "orders")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // No Lombok @Data — use explicit getters/setters or just field access
    // @Version for optimistic locking on contended tables
}
```

## gRPC Caller (order-service → catalog-service)

```java
@Component
public class CatalogServiceGrpcClient {

    private final CatalogServiceGrpc.CatalogServiceBlockingStub stub;

    public CatalogServiceGrpcClient(@Value("${grpc.catalog-service.address:catalog-service.internal.local:9090}") String address) {
        ManagedChannel channel = ManagedChannelBuilder.forTarget(address).usePlaintext().build();
        this.stub = CatalogServiceGrpc.newBlockingStub(channel);
    }

    public CatalogItem getItem(String itemId) {
        return stub.getItem(GetItemRequest.newBuilder().setItemId(itemId).build());
    }
}
```

- `catalog-service` is reachable inside ECS via Cloud Map DNS at `catalog-service.internal.local:9090`
- Wrap calls with Resilience4j `@CircuitBreaker` + `@Retry` — see `/resilience4j-patterns`

## application.yml Template

```yaml
server:
  port: 808X

spring:
  application:
    name: {service}-service
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/{service}_db}
  jpa:
    hibernate:
      ddl-auto: none        # Flyway owns the schema — never validate or create
    show-sql: false
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${COGNITO_ISSUER_URI}   # e.g. https://cognito-idp.{region}.amazonaws.com/{pool-id}

management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics, prometheus
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318/v1/traces}

aws:
  sqs:
    {service}-events-queue-url: ${SQS_{SERVICE}_EVENTS_QUEUE_URL}
```

## Global Exception Handler Pattern

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    public record ErrorResponse(String message) {}

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(NotFoundException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException ex) {
        return new ErrorResponse(ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + ": " + e.getDefaultMessage())
            .collect(Collectors.joining(", ")));
    }
}
```

## Anti-Patterns — Flag Immediately

| Anti-pattern | Fix |
|---|---|
| `System.out.println` | Use `LoggerFactory.getLogger(...)` |
| `@Autowired` on a field | Constructor injection |
| `new Date()` or `LocalDateTime.now()` for event timestamps | `Instant.now()` |
| Missing `@Valid` on `@RequestBody` | Add it |
| Endpoint path without `/api/v1/` prefix | Add prefix |
| JWT claims parsed manually (e.g. splitting the Bearer token) | Use `@AuthenticationPrincipal Jwt jwt` |
| `<version>` inside service `pom.xml` `<dependency>` | Remove — BOM manages versions |
| `ddl-auto: create`, `create-drop`, or `validate` in `application.yml` | Use `none` |
| SQS queue URL hardcoded as a string literal | Read from `${aws.sqs.queue-url}` property |
