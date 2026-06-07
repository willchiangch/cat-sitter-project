package com.petsitter.interfaces.controller;

import com.petsitter.application.exception.KycException;
import com.petsitter.application.service.KycService;
import com.petsitter.domain.model.KycRecord;
import com.petsitter.domain.model.Profile;
import com.petsitter.domain.repository.ProfileRepository;
import com.petsitter.infrastructure.security.TokenContext;
import com.petsitter.infrastructure.security.gating.PlanTier;
import com.petsitter.infrastructure.security.gating.RequirePlan;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/sitter/kyc")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SITTER')")
@RequirePlan(PlanTier.FREE)
public class SitterKycController {

    private final KycService kycService;
    private final ProfileRepository profileRepository;

    @PostMapping
    public ResponseEntity<Map<String, Object>> submitKyc(
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
            @RequestParam("idCardFront") MultipartFile idCardFront,
            @RequestParam("selfie") MultipartFile selfie) {
        
        UUID sitterId = TokenContext.getUserId();
        
        // 1. 限流卡控 (Rate Limiting) -- 於交易邊界外部執行
        kycService.checkRateLimit(sitterId);
        
        KycRecord record = kycService.submitKyc(sitterId, idCardFront, selfie, idempotencyKey);
        
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "實名認證資料提交成功，已進入審核程序",
                "data", Map.of(
                        "recordId", record.getId(),
                        "status", "PENDING_REVIEW"
                )
        ));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getKycStatus() {
        UUID sitterId = TokenContext.getUserId();
        Profile profile = profileRepository.findByUserIdAndType(sitterId, "SITTER")
                .orElseThrow(() -> new KycException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到該保母資料"));
        
        KycRecord record = kycService.getKycStatus(sitterId);
        
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "OK",
                "data", Map.of(
                        "kycStatus", profile.getKycStatus(),
                        "rejectReason", (record != null && record.getRejectReason() != null) ? record.getRejectReason() : "",
                        "submittedAt", record != null ? record.getCreatedAt().toString() : ""
                )
        ));
    }

    @GetMapping("/media/{mediaType}")
    public ResponseEntity<Map<String, Object>> getSignedUrl(@PathVariable String mediaType) {
        UUID sitterId = TokenContext.getUserId();
        String signedUrl = kycService.generateSignedUrl(sitterId, mediaType);
        
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "OK",
                "data", Map.of(
                        "signedUrl", signedUrl
                )
        ));
    }

    @PutMapping("/open")
    public ResponseEntity<Map<String, Object>> updateOpenStatus(@RequestBody Map<String, Boolean> body) {
        UUID sitterId = TokenContext.getUserId();
        boolean isOpen = body.getOrDefault("isOpen", false);
        kycService.updateSitterOpenStatus(sitterId, isOpen);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "更新接單狀態成功",
                "data", Map.of("isOpen", isOpen)
        ));
    }

    @GetMapping("/open")
    public ResponseEntity<Map<String, Object>> getOpenStatus() {
        UUID sitterId = TokenContext.getUserId();
        boolean isOpen = kycService.getSitterOpenStatus(sitterId);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "OK",
                "data", Map.of("isOpen", isOpen)
        ));
    }
}
