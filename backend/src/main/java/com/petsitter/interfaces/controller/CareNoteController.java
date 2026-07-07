package com.petsitter.interfaces.controller;

import com.petsitter.application.dto.*;
import com.petsitter.application.service.CareNoteService;
import com.petsitter.application.service.IdempotencyService;
import com.petsitter.infrastructure.security.TokenContext;
import com.petsitter.infrastructure.security.gating.PlanTier;
import com.petsitter.infrastructure.security.gating.RequirePlan;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/care-notes")
@RequiredArgsConstructor
@RequirePlan(PlanTier.FREE)
public class CareNoteController {

    private final CareNoteService careNoteService;
    private final IdempotencyService idempotencyService;

    // GET /api/care-notes/{sitterId}/{ownerId}
    @GetMapping("/{sitterId}/{ownerId}")
    public ResponseEntity<Map<String, Object>> getCareNote(
            @PathVariable UUID sitterId,
            @PathVariable UUID ownerId) {
        
        UUID userId = TokenContext.getUserId();
        if (!sitterId.equals(userId) && !ownerId.equals(userId)) {
            throw new AccessDeniedException("權限不足");
        }
        
        CareNoteDto dto = careNoteService.getCareNote(sitterId, ownerId);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "OK",
                "data", dto
        ));
    }

    // PUT /api/care-notes/{sitterId}/{ownerId}
    @PutMapping("/{sitterId}/{ownerId}")
    public ResponseEntity<Map<String, Object>> saveCareNote(
            @PathVariable UUID sitterId,
            @PathVariable UUID ownerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CareNoteRequest request) {
        
        if (!sitterId.equals(TokenContext.getUserId())) {
            throw new AccessDeniedException("權限不足");
        }
        
        idempotencyService.checkAndConsume(idempotencyKey, sitterId);
        
        UUID careNoteId = careNoteService.saveCareNote(sitterId, ownerId, request.getItems());
        
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "修改成功",
                "data", Map.of("careNoteId", careNoteId)
        ));
    }

    // GET /api/care-notes/templates
    @GetMapping("/templates")
    public ResponseEntity<Map<String, Object>> getTemplates() {
        UUID sitterId = TokenContext.getUserId();
        List<CareNoteTemplateDto> templates = careNoteService.getTemplates(sitterId);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "data", templates
        ));
    }

    // POST /api/care-notes/templates
    @PostMapping("/templates")
    public ResponseEntity<Map<String, Object>> createTemplate(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CareNoteTemplateRequest request) {
        
        UUID sitterId = TokenContext.getUserId();
        idempotencyService.checkAndConsume(idempotencyKey, sitterId);
        
        try {
            UUID templateId = careNoteService.createTemplate(sitterId, request.getName(), request.getItems());
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "code", 201,
                    "message", "新增成功",
                    "data", Map.of("templateId", templateId)
            ));
        } catch (IllegalArgumentException e) {
            Map<String, Object> response = new HashMap<>();
            response.put("code", 400);
            response.put("message", e.getMessage());
            response.put("data", null);
            return ResponseEntity.badRequest().body(response);
        }
    }

    // PUT /api/care-notes/templates/{templateId}
    @PutMapping("/templates/{templateId}")
    public ResponseEntity<Map<String, Object>> updateTemplate(
            @PathVariable UUID templateId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CareNoteTemplateRequest request) {
        
        UUID sitterId = TokenContext.getUserId();
        idempotencyService.checkAndConsume(idempotencyKey, sitterId);
        
        careNoteService.updateTemplate(sitterId, templateId, request.getName(), request.getItems());
        
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "修改成功",
                "data", Map.of("templateId", templateId)
        ));
    }

    // DELETE /api/care-notes/templates/{templateId}
    @DeleteMapping("/templates/{templateId}")
    public ResponseEntity<Map<String, Object>> deleteTemplate(
            @PathVariable UUID templateId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        
        UUID sitterId = TokenContext.getUserId();
        idempotencyService.checkAndConsume(idempotencyKey, sitterId);
        
        careNoteService.deleteTemplate(sitterId, templateId);

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "刪除成功");
        response.put("data", null);
        return ResponseEntity.ok(response);
    }

    // POST /api/care-notes/{sitterId}/{ownerId}/apply-template/{templateId}
    @PostMapping("/{sitterId}/{ownerId}/apply-template/{templateId}")
    public ResponseEntity<Map<String, Object>> applyTemplate(
            @PathVariable UUID sitterId,
            @PathVariable UUID ownerId,
            @PathVariable UUID templateId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        
        if (!sitterId.equals(TokenContext.getUserId())) {
            throw new AccessDeniedException("權限不足");
        }
        
        idempotencyService.checkAndConsume(idempotencyKey, sitterId);
        
        careNoteService.applyTemplate(sitterId, ownerId, templateId);

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "套用成功");
        response.put("data", null);
        return ResponseEntity.ok(response);
    }
}
