package com.catsitter.api.controller.v1;

import com.catsitter.api.service.storage.StorageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/uploads")
public class UploadController {

    private final StorageService storageService;

    public UploadController(StorageService storageService) {
        this.storageService = storageService;
    }

    /**
     * Generic upload endpoint for profile pictures, pet photos, etc.
     * folder should be something like 'profiles' or 'pets'.
     */
    @PostMapping("/{folder}")
    public ResponseEntity<Map<String, Object>> uploadFile(
            @PathVariable String folder,
            @RequestParam("file") MultipartFile file) {
        
        try {
            // Basic validation for folder names to prevent path traversal
            if (folder.contains("..") || folder.contains("/") || folder.contains("\\")) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid folder name"));
            }

            String filePath = storageService.store(file, folder);
            
            return ResponseEntity.ok(Map.of(
                "url", "/api/v1/media/" + filePath,
                "path", filePath
            ));
        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "Upload failed: " + e.getMessage()));
        }
    }
}
