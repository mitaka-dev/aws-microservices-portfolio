package com.portfolio.orderservice;

import com.portfolio.orderservice.grpc.CatalogGrpcClient;
import com.portfolio.orderservice.grpc.PaymentGrpcClient;
import com.portfolio.orderservice.model.OrderStatus;
import com.portfolio.orderservice.repository.OrderRepository;
import com.portfolio.proto.catalog.DecrementStockResponse;
import com.portfolio.proto.payment.PaymentMethod;
import com.portfolio.proto.payment.PaymentResponse;
import com.portfolio.proto.payment.PaymentStatus;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.SNS;

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
        .withServices(SNS);

    static String topicArn;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);

        r.add("spring.cloud.aws.credentials.access-key", localstack::getAccessKey);
        r.add("spring.cloud.aws.credentials.secret-key", localstack::getSecretKey);
        r.add("spring.cloud.aws.region.static", localstack::getRegion);
        r.add("spring.cloud.aws.endpoint", () -> localstack.getEndpoint().toString());

        createSnsResources();
        r.add("aws.sns.orders-topic-arn", () -> topicArn);
    }

    private static void createSnsResources() {
        var endpoint = localstack.getEndpoint();
        var region = Region.of(localstack.getRegion());
        var creds = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey()));

        try (var snsClient = SnsClient.builder()
                .endpointOverride(endpoint).region(region).credentialsProvider(creds).build()) {
            topicArn = snsClient.createTopic(r -> r.name("orders-events")).topicArn();
        }
    }

    @MockitoBean
    CatalogGrpcClient catalogGrpcClient;

    @MockitoBean
    PaymentGrpcClient paymentGrpcClient;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    OrderRepository orderRepository;

    @BeforeEach
    void setUpMocks() {
        given(catalogGrpcClient.decrementStock(anyString(), anyInt()))
            .willReturn(DecrementStockResponse.newBuilder().setSuccess(true).setRemainingStock(9).build());

        given(paymentGrpcClient.processPayment(anyString(), anyDouble(), anyString(), any(PaymentMethod.class)))
            .willReturn(PaymentResponse.newBuilder()
                .setPaymentId(UUID.randomUUID().toString())
                .setStatus(PaymentStatus.SUCCESS)
                .build());
    }

    @Test
    void createOrder_succeeds_and_is_confirmed() {
        var body = Map.of(
            "userId", "user-123",
            "items", List.of(Map.of("productId", "prod-1", "quantity", 2, "unitPrice", 19.99))
        );
        ResponseEntity<Map> created = restTemplate.postForEntity("/orders", body, Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = (String) created.getBody().get("id");
        assertThat(id).isNotNull();

        assertThat(created.getBody().get("status")).isEqualTo("CONFIRMED");

        ResponseEntity<Map> fetched = restTemplate.getForEntity("/orders/" + id, Map.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().get("userId")).isEqualTo("user-123");
    }

    @Test
    void createOrder_paymentFailure_returns402() {
        given(paymentGrpcClient.processPayment(anyString(), anyDouble(), anyString(), any(PaymentMethod.class)))
            .willReturn(PaymentResponse.newBuilder()
                .setPaymentId(UUID.randomUUID().toString())
                .setStatus(PaymentStatus.FAILED)
                .setFailureReason("Insufficient funds")
                .build());

        var body = Map.of(
            "userId", "user-456",
            "items", List.of(Map.of("productId", "prod-2", "quantity", 1, "unitPrice", 9.99))
        );
        ResponseEntity<Map> response = restTemplate.postForEntity("/orders", body, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
    }

    @Test
    void listOrders() {
        var body = Map.of(
            "userId", "user-789",
            "items", List.of(Map.of("productId", "prod-3", "quantity", 1, "unitPrice", 9.99))
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
