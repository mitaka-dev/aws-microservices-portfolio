package com.portfolio.fileservice.controller;

import com.portfolio.fileservice.dto.PresignDownloadResponse;
import com.portfolio.fileservice.dto.PresignUploadRequest;
import com.portfolio.fileservice.dto.PresignUploadResponse;
import com.portfolio.fileservice.service.FileService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/files")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/presign-upload")
    @ResponseStatus(HttpStatus.CREATED)
    PresignUploadResponse presignUpload(@Valid @RequestBody PresignUploadRequest request) {
        return fileService.presignUpload(request);
    }

    @GetMapping("/{id}/presign-download")
    PresignDownloadResponse presignDownload(@PathVariable String id) {
        return fileService.presignDownload(id);
    }
}
