package com.portfolio.orderservice;

import com.portfolio.orderservice.grpc.CatalogGrpcClient;
import com.portfolio.orderservice.model.OrderStatus;
import com.portfolio.orderservice.repository.OrderRepository;
import com.portfolio.proto.catalog.DecrementStockResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.SNS;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.SQS;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
@Testcontainers
class OrderControllerIT {

    @SuppressWarnings("resource")
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
        DockerImageName.parse("localstack/localstack:3"))
        .withServices(SNS, SQS);

    static String topicArn;
    static String queueUrl;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);

        r.add("spring.cloud.aws.credentials.access-key", localstack::getAccessKey);
        r.add("spring.cloud.aws.credentials.secret-key", localstack::getSecretKey);
        r.add("spring.cloud.aws.region.static", localstack::getRegion);
        r.add("spring.cloud.aws.endpoint", () -> localstack.getEndpoint().toString());

        createMessagingResources();
        r.add("aws.sns.orders-topic-arn", () -> topicArn);
        r.add("aws.sqs.orders-processing-queue-url", () -> queueUrl);
    }

    private static void createMessagingResources() {
        var endpoint = localstack.getEndpoint();
        var region = Region.of(localstack.getRegion());
        var creds = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey()));

        try (var sqsClient = SqsClient.builder().endpointOverride(endpoint).region(region).credentialsProvider(creds).build();
             var snsClient = SnsClient.builder().endpointOverride(endpoint).region(region).credentialsProvider(creds).build()) {

            var dlqUrl = sqsClient.createQueue(r -> r.queueName("orders-dlq")).queueUrl();
            var dlqArn = sqsClient.getQueueAttributes(r -> r.queueUrl(dlqUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);

            queueUrl = sqsClient.createQueue(r -> r
                .queueName("orders-processing")
                .attributes(Map.of(QueueAttributeName.REDRIVE_POLICY,
                    "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"3\"}"))
            ).queueUrl();
            var queueArn = sqsClient.getQueueAttributes(r -> r.queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);

            topicArn = snsClient.createTopic(r -> r.name("orders-events")).topicArn();
            snsClient.subscribe(r -> r
                .topicArn(topicArn)
                .protocol("sqs")
                .endpoint(queueArn)
                .attributes(Map.of("RawMessageDelivery", "true"))
            );
        }
    }

    @MockitoBean
    CatalogGrpcClient catalogGrpcClient;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    OrderRepository orderRepository;

    @BeforeEach
    void setUpMocks() {
        given(catalogGrpcClient.decrementStock(anyString(), anyInt()))
            .willReturn(DecrementStockResponse.newBuilder().setSuccess(true).setRemainingStock(9).build());
    }

    @Test
    void createOrderAndWaitForConfirmation() {
        var body = Map.of(
            "userId", "user-123",
            "items", List.of(Map.of("productId", "prod-1", "quantity", 2, "unitPrice", 19.99))
        );
        ResponseEntity<Map> created = restTemplate.postForEntity("/orders", body, Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String id = (String) created.getBody().get("id");
        assertThat(id).isNotNull();

        UUID orderId = UUID.fromString(id);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(orderRepository.findById(orderId))
                .isPresent()
                .hasValueSatisfying(o -> assertThat(o.getStatus()).isEqualTo(OrderStatus.CONFIRMED))
        );

        ResponseEntity<Map> fetched = restTemplate.getForEntity("/orders/" + id, Map.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().get("userId")).isEqualTo("user-123");
    }

    @Test
    void listOrders() {
        var body = Map.of(
            "userId", "user-456",
            "items", List.of(Map.of("productId", "prod-2", "quantity", 1, "unitPrice", 9.99))
        );
        restTemplate.postForEntity("/orders", body, Map.class);

        ResponseEntity<Object[]> list = restTemplate.getForEntity("/orders", Object[].class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).isNotEmpty();
    }

    @Test
    void getUnknownOrderReturns404() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/orders/" + UUID.randomUUID(), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
