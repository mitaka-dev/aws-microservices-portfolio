package com.portfolio.catalogservice;

import com.portfolio.catalogservice.repository.CatalogItemRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.DYNAMODB;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
@Testcontainers
class CatalogControllerIT {

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
        DockerImageName.parse("localstack/localstack:3"))
        .withServices(DYNAMODB);

    @SuppressWarnings("resource")
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.aws.dynamodb.endpoint", () -> localstack.getEndpointOverride(DYNAMODB).toString());
        registry.add("spring.cloud.aws.credentials.access-key", localstack::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", localstack::getSecretKey);
        registry.add("spring.cloud.aws.region.static", localstack::getRegion);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @BeforeAll
    static void createTable() {
        var dynamoDbClient = DynamoDbClient.builder()
            .endpointOverride(localstack.getEndpointOverride(DYNAMODB))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
            .region(Region.of(localstack.getRegion()))
            .build();

        dynamoDbClient.createTable(r -> r
            .tableName(CatalogItemRepository.TABLE_NAME)
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .keySchema(
                KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()
            )
            .attributeDefinitions(
                AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build(),
                AttributeDefinition.builder().attributeName("gsi1pk").attributeType(ScalarAttributeType.S).build(),
                AttributeDefinition.builder().attributeName("gsi1sk").attributeType(ScalarAttributeType.S).build()
            )
            .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                .indexName("GSI1")
                .keySchema(
                    KeySchemaElement.builder().attributeName("gsi1pk").keyType(KeyType.HASH).build(),
                    KeySchemaElement.builder().attributeName("gsi1sk").keyType(KeyType.RANGE).build()
                )
                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                .build())
        );
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void createAndGetItem() {
        var body = Map.of("name", "Widget", "category", "Electronics", "price", 9.99, "stock", 100);
        ResponseEntity<Map> created = restTemplate.postForEntity("/catalog", body, Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String id = (String) created.getBody().get("id");
        assertThat(id).isNotNull();

        ResponseEntity<Map> fetched = restTemplate.getForEntity("/catalog/" + id, Map.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().get("name")).isEqualTo("Widget");
    }

    @Test
    void listItems() {
        var body = Map.of("name", "Gadget", "category", "Tools", "price", 19.99, "stock", 50);
        restTemplate.postForEntity("/catalog", body, Map.class);

        ResponseEntity<Map> list = restTemplate.getForEntity("/catalog?size=10", Map.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody().get("items")).isInstanceOf(List.class);
        assertThat((List<?>) list.getBody().get("items")).isNotEmpty();
    }

    @Test
    void getUnknownItemReturns404() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/catalog/nonexistent", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("error")).isEqualTo("Item not found");
        assertThat(response.getBody().get("status")).isEqualTo(404);
    }
}
