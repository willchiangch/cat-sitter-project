package com.catsitter.api.controller.v1;

import com.catsitter.api.entity.VisitMedia;
import com.catsitter.api.service.MediaService;
import com.catsitter.api.service.storage.StorageService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class MediaController {

    private final MediaService mediaService;
    private final StorageService storageService;

    public MediaController(MediaService mediaService,
                           StorageService storageService) {
        this.mediaService = mediaService;
        this.storageService = storageService;
    }

    /**
     * Upload a photo or video to a specific visit record.
     */
    @PostMapping(value = "/visits/{visitId}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadMedia(
            @PathVariable UUID visitId,
            @RequestParam("file") MultipartFile file) throws IOException {
        
        VisitMedia media = mediaService.uploadVisitMedia(visitId, file);
        return ResponseEntity.ok(Map.of(
            "id", media.getId(),
            "url", "/api/v1/media/" + media.getMediaUrl(),
            "type", media.getMediaType(),
            "size", media.getFileSize()
        ));
    }

    /**
     * Serves locally stored media files. 
     * Uses a robust path extraction to avoid issues with different servlet containers or proxying.
     */
    @GetMapping("/media/{*path}")
    public ResponseEntity<Resource> serveFile(@PathVariable String path, 
                                             jakarta.servlet.http.HttpServletRequest request) {
        // Remove leading slash if any
        String cleanedPath = (path.startsWith("/")) ? path.substring(1) : path;
        
        try {
            Resource file = storageService.load(cleanedPath);
            
            if (file == null || !file.exists()) {
                System.out.println("[MEDIA] File not found: " + cleanedPath);
                return ResponseEntity.notFound().build();
            }

            System.out.println("[MEDIA] Serving file: " + file.getURI());

            String contentType = "application/octet-stream";
            try {
                String fileName = file.getFilename();
                if (fileName != null) {
                    contentType = request.getServletContext().getMimeType(fileName);
                }
                if (contentType == null) {
                    if (fileName != null && fileName.toLowerCase().endsWith(".jpg")) contentType = "image/jpeg";
                    else if (fileName != null && fileName.toLowerCase().endsWith(".png")) contentType = "image/png";
                }
            } catch (Exception ex) {
                // Fallback to default
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getFilename() + "\"")
                    .body(file);
        } catch (Exception e) {
            System.err.println("[MEDIA] Error serving file " + cleanedPath + ": " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.notFound().build();
        }
    }
}
