package com.petsitter.interfaces.controller;

import com.petsitter.application.dto.CareMediaDto;
import com.petsitter.application.service.CareMediaService;
import com.petsitter.application.service.IdempotencyService;
import com.petsitter.infrastructure.security.TokenContext;
import com.petsitter.infrastructure.security.gating.PlanTier;
import com.petsitter.infrastructure.security.gating.RequirePlan;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/care-media")
@RequiredArgsConstructor
@RequirePlan(PlanTier.FREE)
public class CareMediaController {

    private final CareMediaService careMediaService;
    private final IdempotencyService idempotencyService;

    // GET /api/care-media/{sitterId}/{ownerId}
    @GetMapping("/{sitterId}/{ownerId}")
    public ResponseEntity<Map<String, Object>> getMediaList(
            @PathVariable UUID sitterId,
            @PathVariable UUID ownerId) {
        
        UUID userId = TokenContext.getUserId();
        if (!sitterId.equals(userId) && !ownerId.equals(userId)) {
            throw new AccessDeniedException("權限不足");
        }
        
        List<CareMediaDto> data = careMediaService.getMediaList(sitterId, ownerId);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "data", data
        ));
    }

    // POST /api/care-media/{sitterId}/{ownerId}
    @PostMapping("/{sitterId}/{ownerId}")
    public ResponseEntity<Map<String, Object>> uploadMedia(
            @PathVariable UUID sitterId,
            @PathVariable UUID ownerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam("caption") String caption,
            @RequestParam("mediaType") String mediaType,
            @RequestParam("file") MultipartFile file) {
        
        if (!sitterId.equals(TokenContext.getUserId())) {
            throw new AccessDeniedException("權限不足");
        }
        
        idempotencyService.checkAndConsume(idempotencyKey, sitterId);
        
        try {
            CareMediaDto dto = careMediaService.uploadMedia(sitterId, ownerId, caption, mediaType, file);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "code", 201,
                    "data", Map.of(
                            "mediaId", dto.getId(),
                            "mediaUrl", dto.getMediaUrl()
                    )
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", 400,
                    "message", e.getMessage()
            ));
        }
    }

    // DELETE /api/care-media/{mediaId}
    @DeleteMapping("/{mediaId}")
    public ResponseEntity<Map<String, Object>> deleteMedia(
            @PathVariable UUID mediaId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        
        UUID sitterId = TokenContext.getUserId();
        idempotencyService.checkAndConsume(idempotencyKey, sitterId);
        
        careMediaService.deleteMedia(sitterId, mediaId);

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "刪除成功");
        response.put("data", null);
        return ResponseEntity.ok(response);
    }
}
