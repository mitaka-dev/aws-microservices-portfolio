package com.portfolio.fileservice;

import com.portfolio.fileservice.dto.PresignDownloadResponse;
import com.portfolio.fileservice.dto.PresignUploadRequest;
import com.portfolio.fileservice.dto.PresignUploadResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
@Testcontainers
class FileControllerIT {

    static final String BUCKET = "test-files";

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
        DockerImageName.parse("localstack/localstack:3"))
        .withServices(S3);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("aws.s3.endpoint", () -> localstack.getEndpointOverride(S3).toString());
        r.add("aws.s3.access-key", localstack::getAccessKey);
        r.add("aws.s3.secret-key", localstack::getSecretKey);
        r.add("aws.s3.region", localstack::getRegion);
        r.add("aws.s3.bucket-name", () -> BUCKET);
        r.add("aws.s3.path-style-access", () -> "true");

        createBucket();
    }

    private static void createBucket() {
        try (var s3 = S3Client.builder()
            .endpointOverride(localstack.getEndpointOverride(S3))
            .region(Region.of(localstack.getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()) {
            s3.createBucket(r -> r.bucket(BUCKET));
        }
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void presignUploadReturnsFileIdAndUrl() {
        var resp = restTemplate.postForEntity("/files/presign-upload",
            new PresignUploadRequest("photo.jpg", "image/jpeg"),
            PresignUploadResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().fileId()).isNotBlank();
        assertThat(resp.getBody().uploadUrl()).startsWith("http");
        assertThat(resp.getBody().uploadUrl()).contains(BUCKET);
    }

    @Test
    void uploadAndDownloadRoundTrip() throws Exception {
        var uploadResp = restTemplate.postForEntity("/files/presign-upload",
            new PresignUploadRequest("hello.txt", "text/plain"),
            PresignUploadResponse.class);
        assertThat(uploadResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String fileId = uploadResp.getBody().fileId();
        String uploadUrl = uploadResp.getBody().uploadUrl();

        // PUT the file content via the presigned URL
        var httpClient = HttpClient.newHttpClient();
        var putRequest = HttpRequest.newBuilder()
            .uri(URI.create(uploadUrl))
            .method("PUT", HttpRequest.BodyPublishers.ofString("hello world"))
            .header("Content-Type", "text/plain")
            .build();
        var putResponse = httpClient.send(putRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(putResponse.statusCode()).isEqualTo(200);

        // GET presigned download URL
        var downloadResp = restTemplate.getForEntity(
            "/files/{id}/presign-download", PresignDownloadResponse.class, fileId);
        assertThat(downloadResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(downloadResp.getBody().downloadUrl()).startsWith("http");

        // Verify the object exists in S3 (via direct S3 client)
        try (var s3 = S3Client.builder()
            .endpointOverride(localstack.getEndpointOverride(S3))
            .region(Region.of(localstack.getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()) {
            var obj = s3.getObjectAsBytes(r -> r.bucket(BUCKET).key("uploads/" + fileId));
            assertThat(obj.asUtf8String()).isEqualTo("hello world");
        }
    }

    @Test
    void presignDownloadReturnsUrlWithoutUpload() {
        var resp = restTemplate.getForEntity(
            "/files/{id}/presign-download", PresignDownloadResponse.class, "nonexistent-id");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().downloadUrl()).startsWith("http");
    }
}
