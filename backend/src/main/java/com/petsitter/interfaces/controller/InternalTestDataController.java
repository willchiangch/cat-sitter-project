package com.petsitter.interfaces.controller;

import com.petsitter.application.dto.RegisterRequest;
import com.petsitter.application.service.E2eJourneyAccountProvisioningService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 供 frontend/e2e/journeys/ 真後端 journey 測試呼叫的內部端點，掛在 InternalSecretFilter 保護下
 * （/api/internal/** 一律需要 X-Internal-Secret）。
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/test-data")
@RequiredArgsConstructor
public class InternalTestDataController {

    private final E2eJourneyAccountProvisioningService provisioningService;

    /**
     * 直接建立「等同 OTP 已驗證完成」狀態的帳號，略過寄信/驗證這一步。
     * journey 測試建好帳號後仍會走真實的 POST /api/auth/login 取得 token。
     */
    @PostMapping("/provision-account")
    public ResponseEntity<Map<String, Object>> provisionAccount(@Valid @RequestBody RegisterRequest request) {
        UUID userId = provisioningService.provisionAccount(
                request.getEmail(), request.getPassword(), request.getFullName(), request.getRole());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("status", "SUCCESS", "userId", userId));
    }
}
