package com.portfolio.fileservice.service;

import com.portfolio.fileservice.dto.PresignDownloadResponse;
import com.portfolio.fileservice.dto.PresignUploadRequest;
import com.portfolio.fileservice.dto.PresignUploadResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.UUID;

@Service
public class FileService {

    private final S3Presigner presigner;
    private final String bucketName;
    private final int presignMinutes;

    public FileService(S3Presigner presigner,
                       @Value("${aws.s3.bucket-name}") String bucketName,
                       @Value("${aws.s3.presign-duration-minutes:15}") int presignMinutes) {
        this.presigner = presigner;
        this.bucketName = bucketName;
        this.presignMinutes = presignMinutes;
    }

    public PresignUploadResponse presignUpload(PresignUploadRequest request) {
        String fileId = UUID.randomUUID().toString();
        String key = "uploads/" + fileId;

        var presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(presignMinutes))
            .putObjectRequest(r -> r
                .bucket(bucketName)
                .key(key)
                .contentType(request.contentType()))
            .build();

        var presigned = presigner.presignPutObject(presignRequest);
        return new PresignUploadResponse(fileId, presigned.url().toString());
    }

    public PresignDownloadResponse presignDownload(String fileId) {
        String key = "uploads/" + fileId;

        var presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(presignMinutes))
            .getObjectRequest(r -> r.bucket(bucketName).key(key))
            .build();

        var presigned = presigner.presignGetObject(presignRequest);
        return new PresignDownloadResponse(presigned.url().toString());
    }
}
