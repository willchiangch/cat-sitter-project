package com.catsitter.api.controller.v1;

import com.catsitter.api.service.storage.StorageService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageService storageService;

    /**
     * Generate a signed URL for client-side upload.
     * Expects filename and subFolder in the request body.
     */
    @PostMapping("/upload-url")
    public ResponseEntity<Map<String, String>> getUploadUrl(@RequestBody UploadRequest request) {
        String uploadUrl = storageService.generateUploadUrl(request.getFileName(), request.getSubFolder());
        return ResponseEntity.ok(Map.of("uploadUrl", uploadUrl));
    }

    @Data
    public static class UploadRequest {
        private String fileName;
        private String subFolder;
    }
}
