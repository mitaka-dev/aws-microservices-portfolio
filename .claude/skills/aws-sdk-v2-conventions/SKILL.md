---
name: aws-sdk-v2-conventions
description: AWS SDK v2 patterns for DynamoDB in this project. Use when working on order-service or any service that uses DynamoDB or imports software.amazon.awssdk.
allowed-tools: Read, Edit, Write
---

# AWS SDK v2 Conventions

This project uses `software.amazon.awssdk` v2 (managed via BOM `${aws-java-sdk.version}` in root pom.xml).
`order-service` uses DynamoDB for order storage.

## DynamoDbConfig Pattern

```java
// config/DynamoDbConfig.java
@Configuration
public class DynamoDbConfig {

    @Value("${aws.region:eu-west-1}")
    private String region;

    @Value("${aws.dynamodb.endpoint:}")   // empty = real AWS; set for LocalStack
    private String dynamoDbEndpoint;

    @Bean
    public DynamoDbClient dynamoDbClient() {
        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(region));
        if (!dynamoDbEndpoint.isBlank()) {
            builder.endpointOverride(URI.create(dynamoDbEndpoint));
        }
        return builder.build();
    }

    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient client) {
        return DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();
    }
}
```

## DynamoDB Entity — Must Be a Mutable JavaBean

**DynamoDB Enhanced Client cannot use Java records.** Entities must be:
- Mutable JavaBeans (no-arg constructor + getters/setters)
- Annotated with `@DynamoDbBean` on the class
- `@DynamoDbPartitionKey` on the **getter**, not the field

```java
@DynamoDbBean
public class OrderRecord {

    private String orderId;
    private String userId;
    private String status;    // store enum as String via enum.name()
    private String createdAt; // ISO-8601 String, e.g. Instant.now().toString()

    public OrderRecord() {}  // required

    @DynamoDbPartitionKey
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
```

## Service Pattern

```java
@Service
public class OrderRecordService {

    private final DynamoDbTable<OrderRecord> orderTable;

    public OrderRecordService(DynamoDbEnhancedClient enhancedClient) {
        this.orderTable = enhancedClient.table("orders-dynamo", TableSchema.fromBean(OrderRecord.class));
    }

    public OrderRecord createOrder(String userId, String item) {
        OrderRecord record = new OrderRecord();
        record.setOrderId(UUID.randomUUID().toString());
        record.setUserId(userId);
        record.setStatus(OrderStatus.PENDING.name());
        record.setCreatedAt(Instant.now().toString());
        orderTable.putItem(record);
        return record;
    }

    public OrderRecord getOrder(String orderId) {
        OrderRecord result = orderTable.getItem(Key.builder().partitionValue(orderId).build());
        if (result == null) throw new OrderNotFoundException(orderId);
        return result;
    }
}
```

## Scan with Filter Expression

When filtering by a non-key attribute, use a scan with a filter expression.
**Always use expression attribute names (`#attr`) for field names that might clash with DynamoDB reserved words** (e.g., `status`, `name`, `orderId`).

```java
public List<OrderRecord> getByUserId(String userId) {
    ScanEnhancedRequest request = ScanEnhancedRequest.builder()
        .filterExpression(Expression.builder()
            .expression("#ui = :userId")
            .expressionNames(Map.of("#ui", "userId"))   // escape potential reserved word
            .expressionValues(Map.of(":userId", AttributeValue.fromS(userId)))
            .build())
        .build();

    // SdkIterable does NOT implement Collection — bridge to Stream:
    return StreamSupport.stream(orderTable.scan(request).items().spliterator(), false)
            .map(this::toDto)
            .collect(Collectors.toList());
}
```

## Required Imports

```java
import software.amazon.awssdk.enhanced.dynamodb.*;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;
import software.amazon.awssdk.enhanced.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
```

## application.yml

```yaml
aws:
  region: ${AWS_REGION:eu-west-1}
  dynamodb:
    endpoint: ${AWS_DYNAMODB_ENDPOINT:}   # empty = real AWS; set to http://localhost:4566 for LocalStack
```

## Anti-Patterns

| Anti-pattern | Fix |
|---|---|
| Using a Java record as a DynamoDB entity | Use a mutable JavaBean with `@DynamoDbBean` |
| `@DynamoDbPartitionKey` on a field | Must be on the getter method |
| Calling `.items()` on `SdkIterable` as a Collection | Use `StreamSupport.stream(...)` |
| Filtering by non-key attribute without `expressionNames` | Always alias with `#attr` to avoid reserved word collisions |
