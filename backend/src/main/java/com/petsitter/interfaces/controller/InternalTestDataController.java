package com.petsitter.interfaces.controller;

import com.petsitter.application.dto.order.TestDataRequests;
import com.petsitter.application.service.E2eTestSetupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 真後端 journey 測試用內部端點（{@code X-Internal-Secret}，SD-ORDER §10.4）：provision-account（roles[] / sitterProfile /
 * displayName）、sitter-setup / owner-setup（upsert，回傳 key 對應的 id）。訂單 inspect / shift-time 見
 * {@code InternalOrderTestDataController}。只接受 {@code @e2e-journey.test} 帳號。
 */
@RestController
@RequestMapping("/api/internal/test-data")
@RequiredArgsConstructor
public class InternalTestDataController {

    private final E2eTestSetupService setupService;

    @PostMapping("/provision-account")
    public ResponseEntity<Map<String, Object>> provisionAccount(@RequestBody TestDataRequests.ProvisionAccount request) {
        UUID userId = setupService.provision(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("status", "SUCCESS", "userId", userId));
    }

    @PostMapping("/sitter-setup")
    public Map<String, Object> sitterSetup(@RequestBody TestDataRequests.SitterSetup request) {
        return setupService.sitterSetup(request);
    }

    @PostMapping("/owner-setup")
    public Map<String, Object> ownerSetup(@RequestBody TestDataRequests.OwnerSetup request) {
        return setupService.ownerSetup(request);
    }
}
