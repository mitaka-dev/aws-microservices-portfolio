package com.portfolio.paymentservice;

import com.portfolio.paymentservice.model.PaymentStatus;
import com.portfolio.paymentservice.repository.PaymentRecordRepository;
import com.portfolio.proto.payment.PaymentMethod;
import com.portfolio.proto.payment.PaymentRequest;
import com.portfolio.proto.payment.PaymentResponse;
import com.portfolio.proto.payment.PaymentServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
class PaymentGrpcServiceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    static ManagedChannel channel;
    static PaymentServiceGrpc.PaymentServiceBlockingStub stub;

    @BeforeAll
    static void startGrpcChannel() {
        channel = ManagedChannelBuilder.forAddress("localhost", 9090)
            .usePlaintext()
            .build();
        stub = PaymentServiceGrpc.newBlockingStub(channel);
    }

    @AfterAll
    static void stopGrpcChannel() {
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    @Autowired
    PaymentRecordRepository paymentRecordRepository;

    @Test
    void creditCardPayment_succeeds_and_persists_record() {
        var request = PaymentRequest.newBuilder()
            .setOrderId(UUID.randomUUID().toString())
            .setAmount(99.99)
            .setCurrency("USD")
            .setMethod(PaymentMethod.CREDIT_CARD)
            .build();

        PaymentResponse response = stub.processPayment(request);

        assertThat(response.getStatus()).isEqualTo(com.portfolio.proto.payment.PaymentStatus.SUCCESS);
        assertThat(response.getPaymentId()).isNotBlank();

        var record = paymentRecordRepository.findById(java.util.UUID.fromString(response.getPaymentId()));
        assertThat(record).isPresent();
        assertThat(record.get().getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(record.get().getOrderId()).isEqualTo(request.getOrderId());
    }

    @Test
    void paypalPayment_succeeds_and_persists_record() {
        var request = PaymentRequest.newBuilder()
            .setOrderId(UUID.randomUUID().toString())
            .setAmount(49.99)
            .setCurrency("EUR")
            .setMethod(PaymentMethod.PAYPAL)
            .build();

        PaymentResponse response = stub.processPayment(request);

        assertThat(response.getStatus()).isEqualTo(com.portfolio.proto.payment.PaymentStatus.SUCCESS);
        assertThat(response.getPaymentId()).isNotBlank();
    }

    @Test
    void bankTransferPayment_succeeds_and_persists_record() {
        var request = PaymentRequest.newBuilder()
            .setOrderId(UUID.randomUUID().toString())
            .setAmount(250.00)
            .setCurrency("GBP")
            .setMethod(PaymentMethod.BANK_TRANSFER)
            .build();

        PaymentResponse response = stub.processPayment(request);

        assertThat(response.getStatus()).isEqualTo(com.portfolio.proto.payment.PaymentStatus.SUCCESS);
        assertThat(response.getPaymentId()).isNotBlank();
    }
}
