package com.portfolio.fileservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
public class S3Config {

    @Bean
    S3Client s3Client(
        @Value("${aws.s3.region:eu-west-1}") String region,
        @Value("${aws.s3.endpoint:}") String endpoint,
        @Value("${aws.s3.access-key:}") String accessKey,
        @Value("${aws.s3.secret-key:}") String secretKey,
        @Value("${aws.s3.path-style-access:false}") boolean pathStyleAccess
    ) {
        var s3Config = S3Configuration.builder().pathStyleAccessEnabled(pathStyleAccess).build();
        var builder = S3Client.builder()
            .region(Region.of(region))
            .serviceConfiguration(s3Config);
        if (!endpoint.isEmpty()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        if (!accessKey.isEmpty()) {
            builder.credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        }
        return builder.build();
    }

    @Bean
    S3Presigner s3Presigner(
        @Value("${aws.s3.region:eu-west-1}") String region,
        @Value("${aws.s3.endpoint:}") String endpoint,
        @Value("${aws.s3.access-key:}") String accessKey,
        @Value("${aws.s3.secret-key:}") String secretKey,
        @Value("${aws.s3.path-style-access:false}") boolean pathStyleAccess
    ) {
        var s3Config = S3Configuration.builder().pathStyleAccessEnabled(pathStyleAccess).build();
        var builder = S3Presigner.builder()
            .region(Region.of(region))
            .serviceConfiguration(s3Config);
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
