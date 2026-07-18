package com.petsitter.interfaces.controller;

import com.petsitter.application.exception.KycException;
import com.petsitter.application.service.KycService;
import com.petsitter.domain.model.KycRecord;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.UserRepository;
import com.petsitter.infrastructure.security.TokenContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminKycController {

    private final KycService kycService;
    private final UserRepository userRepository;

    @GetMapping("/kyc/pending")
    public ResponseEntity<Map<String, Object>> getPendingKycRecords(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        
        Pageable pageable = PageRequest.of(page, size);
        Page<Object[]> recordPage = kycService.getPendingKycRecordsWithUser(pageable);
        
        List<Map<String, Object>> content = recordPage.getContent().stream().map(row -> {
            KycRecord record = (KycRecord) row[0];
            User user = (User) row[1];
            return Map.<String, Object>of(
                    "recordId", record.getId(),
                    "sitterId", record.getSitterId(),
                    "fullName", user.getFullName(),
                    "email", user.getEmail(),
                    "kycStatus", record.getStatus(),
                    "submittedAt", record.getCreatedAt().toString()
            );
        }).collect(Collectors.toList());
        
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "OK",
                "data", Map.of(
                        "content", content,
                        "page", recordPage.getNumber(),
                        "size", recordPage.getSize(),
                        "totalElements", recordPage.getTotalElements(),
                        "totalPages", recordPage.getTotalPages()
                )
        ));
    }

    @GetMapping("/kyc/{recordId}")
    public ResponseEntity<Map<String, Object>> getKycRecordDetail(@PathVariable UUID recordId) {
        KycRecord record = kycService.getKycRecordDetail(recordId);
        User user = userRepository.findById(record.getSitterId())
                .orElseThrow(() -> new KycException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到該保母資料"));
        
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "OK",
                "data", Map.of(
                        "recordId", record.getId(),
                        "sitterId", record.getSitterId(),
                        "fullName", user.getFullName(),
                        "email", user.getEmail(),
                        "kycStatus", record.getStatus(),
                        "submittedAt", record.getCreatedAt().toString(),
                        "idCardFrontKey", record.getIdCardFrontKey(),
                        "selfieKey", record.getSelfieKey()
                )
        ));
    }

    @GetMapping("/kyc/{sitterId}/media/{mediaType}")
    public ResponseEntity<Map<String, Object>> getAdminSignedUrl(
            @PathVariable UUID sitterId,
            @PathVariable String mediaType) {
        
        String signedUrl = kycService.generateAdminSignedUrl(sitterId, mediaType);
        
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "OK",
                "data", Map.of(
                        "signedUrl", signedUrl
                )
        ));
    }

    @PostMapping("/kyc/{recordId}/review")
    public ResponseEntity<Map<String, Object>> reviewKyc(
            @PathVariable UUID recordId,
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> body) {
        
        UUID adminId = TokenContext.getUserId();
        String action = (String) body.get("action");
        String rejectReason = (String) body.get("rejectReason");
        
        kycService.reviewKyc(recordId, adminId, action, rejectReason, idempotencyKey);
        
        return successResponse("審核結果處理成功");
    }

    @PostMapping("/sitters/{sitterId}/suspend")
    public ResponseEntity<Map<String, Object>> suspendSitter(
            @PathVariable UUID sitterId,
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> body) {
        
        UUID adminId = TokenContext.getUserId();
        String reason = (String) body.get("reason");
        
        kycService.suspendSitter(sitterId, adminId, reason, idempotencyKey);
        
        return successResponse("已成功將該保母停權，接單功能已強制關閉");
    }

    @PostMapping("/sitters/{sitterId}/unsuspend")
    public ResponseEntity<Map<String, Object>> unsuspendSitter(
            @PathVariable UUID sitterId,
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey) {

        UUID adminId = TokenContext.getUserId();
        kycService.unsuspendSitter(sitterId, adminId, idempotencyKey);

        return successResponse("已成功解除保母停權狀態，接單資格已恢復");
    }

    /**
     * 內部信用指標管理清單 (PRD-020 主流程 E)：僅供管理後台使用，飼主/保母前台不可查看
     */
    @GetMapping("/sitters/trust-scores")
    public ResponseEntity<Map<String, Object>> listSitterTrustScores() {
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "OK",
                "data", kycService.listSitterTrustScores()
        ));
    }

    /**
     * 管理員手動增減保母信用指標點數 (PRD-020 主流程 E / AC-5)
     */
    @PostMapping("/sitters/{sitterId}/trust-score/adjust")
    public ResponseEntity<Map<String, Object>> adjustTrustScore(
            @PathVariable UUID sitterId,
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> body) {

        UUID adminId = TokenContext.getUserId();
        int delta = ((Number) body.get("delta")).intValue();
        String reason = (String) body.get("reason");

        kycService.adjustTrustScore(sitterId, adminId, delta, reason, idempotencyKey);

        return successResponse("信用指標已成功更新");
    }

    private ResponseEntity<Map<String, Object>> successResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", message);
        response.put("data", null);
        return ResponseEntity.ok(response);
    }
}
