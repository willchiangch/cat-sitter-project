package com.petsitter.interfaces.controller;

import com.petsitter.application.dto.ReportMediaDto;
import com.petsitter.application.dto.VisitServiceReportDto;
import com.petsitter.application.service.VisitReportService;
import com.petsitter.infrastructure.security.TokenContext;
import com.petsitter.infrastructure.security.gating.PlanTier;
import com.petsitter.infrastructure.security.gating.RequirePlan;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/visits")
@RequiredArgsConstructor
@RequirePlan(PlanTier.FREE)
public class VisitReportController {

    private final VisitReportService reportService;

    @PutMapping("/{visitId}/report")
    public ResponseEntity<Map<String, Object>> saveDraft(
            @PathVariable UUID visitId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> body) {

        UUID sitterId = TokenContext.getUserId();
        String content = (String) body.get("content");
        Integer version = ((Number) body.get("version")).intValue();

        VisitServiceReportDto dto = reportService.saveDraft(visitId, content, version, sitterId, idempotencyKey);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "修改成功",
                "data", dto
        ));
    }

    @PostMapping("/{visitId}/media")
    public ResponseEntity<Map<String, Object>> uploadMedia(
            @PathVariable UUID visitId,
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
            @RequestParam("mediaType") String mediaType,
            @RequestParam(value = "caption", required = false) String caption,
            @RequestParam("file") MultipartFile file) {

        UUID sitterId = TokenContext.getUserId();
        ReportMediaDto dto = reportService.uploadMedia(visitId, file, caption, mediaType, sitterId, idempotencyKey);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "新增成功",
                "data", dto
        ));
    }

    @DeleteMapping("/media/{mediaId}")
    public ResponseEntity<Map<String, Object>> deleteMedia(
            @PathVariable UUID mediaId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> body) {

        UUID sitterId = TokenContext.getUserId();
        Integer version = ((Number) body.get("version")).intValue();

        reportService.deleteMedia(mediaId, version, sitterId, idempotencyKey);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "刪除成功",
                "data", null
        ));
    }

    @PostMapping("/{visitId}/report/submit")
    public ResponseEntity<Map<String, Object>> submitReport(
            @PathVariable UUID visitId,
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey) {

        UUID sitterId = TokenContext.getUserId();
        reportService.submitReport(visitId, sitterId, idempotencyKey);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "修改成功",
                "data", null
        ));
    }

    @GetMapping("/{visitId}/report")
    public ResponseEntity<Map<String, Object>> getReport(
            @PathVariable UUID visitId) {

        UUID userId = TokenContext.getUserId();
        VisitServiceReportDto dto = reportService.getReport(visitId, userId);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "OK",
                "data", dto
        ));
    }
}
