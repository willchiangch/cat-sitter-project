package com.petsitter.interfaces.controller;

import com.petsitter.application.service.PaymentProofAclMigrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 一次性維運端點（非排程）。/api/internal/** 一律需要 X-Internal-Secret (InternalSecretFilter)。
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/maintenance")
@RequiredArgsConstructor
public class InternalMaintenanceController {

    private final PaymentProofAclMigrationService paymentProofAclMigrationService;

    /**
     * 將既有公開的付款憑證物件改為私有 (SD-007 §5.1.1)。預設 dryRun=true 只回報不修改，
     * 帶 dryRun=false 才會真的移除 allUsers ACL；可重複執行。
     */
    @PostMapping("/payment-proofs/revoke-public-acl")
    public ResponseEntity<Map<String, Object>> revokePaymentProofPublicAcl(
            @RequestParam(defaultValue = "true") boolean dryRun) {
        log.info("[InternalMaintenanceController] revoke payment proof public ACL, dryRun={}", dryRun);
        return ResponseEntity.ok(paymentProofAclMigrationService.revokePublicAcl(dryRun));
    }
}
