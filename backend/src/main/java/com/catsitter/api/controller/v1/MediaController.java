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
     * In production with GCS, this would be replaced by direct GCS URLs.
     */
    @GetMapping("/media/**")
    public ResponseEntity<Resource> serveFile(@RequestHeader HttpHeaders headers, 
                                             jakarta.servlet.http.HttpServletRequest request) {
        String path = request.getRequestURI().split("/api/v1/media/")[1];
        Resource file = storageService.load(path);
        
        String contentType = "application/octet-stream";
        try {
            contentType = request.getServletContext().getMimeType(file.getFile().getAbsolutePath());
        } catch (IOException ex) {
            // Log warning or keep default
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getFilename() + "\"")
                .body(file);
    }
}
