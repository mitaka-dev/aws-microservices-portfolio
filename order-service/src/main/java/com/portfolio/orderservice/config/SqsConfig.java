package com.portfolio.orderservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.net.URI;

/**
 * Manual SQS client configuration.
 * SqsAutoConfiguration is excluded because it calls PropertyMapper.alwaysApplyingWhenNonNull()
 * which was removed in Spring Boot 4. SqsAsyncClient is wired directly from spring.cloud.aws.*
 * properties instead.
 */
@Configuration
public class SqsConfig {

    @Bean
    SqsAsyncClient sqsAsyncClient(
        @Value("${spring.cloud.aws.region.static:eu-west-1}") String region,
        @Value("${spring.cloud.aws.endpoint:}") String endpoint,
        @Value("${spring.cloud.aws.credentials.access-key:}") String accessKey,
        @Value("${spring.cloud.aws.credentials.secret-key:}") String secretKey
    ) {
        var builder = SqsAsyncClient.builder().region(Region.of(region));
        if (!endpoint.isEmpty()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        if (!accessKey.isEmpty()) {
            builder.credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        }
        return builder.build();
    }
}
