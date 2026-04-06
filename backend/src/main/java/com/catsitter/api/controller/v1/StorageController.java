package com.catsitter.api.controller.v1;

import com.catsitter.api.service.storage.StorageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/storage")
public class StorageController {

    private final StorageService storageService;

    public StorageController(StorageService storageService) {
        this.storageService = storageService;
    }

    /**
     * Generate a signed URL for client-side upload.
     * Expects filename and subFolder in the request body.
     */
    @PostMapping("/upload-url")
    public ResponseEntity<Map<String, String>> getUploadUrl(@RequestBody UploadRequest request) {
        String uploadUrl = storageService.generateUploadUrl(request.getFileName(), request.getSubFolder());
        return ResponseEntity.ok(Map.of("uploadUrl", uploadUrl));
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, String>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "subFolder", defaultValue = "uploads") String subFolder) throws IOException {
        String filePath = storageService.store(file, subFolder);
        // Return relative path; callers (frontend) should resolve via /api/v1/media/{path}
        return ResponseEntity.ok(Map.of("url", "/api/v1/media/" + filePath));
    }

    public static class UploadRequest {
        private String fileName;
        private String subFolder;

        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public String getSubFolder() { return subFolder; }
        public void setSubFolder(String subFolder) { this.subFolder = subFolder; }
    }
}
