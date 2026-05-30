package com.portfolio.fileservice.dto;

import jakarta.validation.constraints.NotBlank;

public record PresignUploadRequest(
    @NotBlank String filename,
    @NotBlank String contentType
) {}
