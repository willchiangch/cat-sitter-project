package com.petsitter.interfaces.controller;

import com.petsitter.application.service.AuditLogService;
import com.petsitter.domain.model.Subscription;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.SubscriptionRepository;
import com.petsitter.domain.repository.UserRepository;
import com.petsitter.infrastructure.security.TokenContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/subscriptions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminSubscriptionController {

    private static final Set<String> VALID_TIERS = Set.of("FREE", "BASIC", "PRO", "ULTIMATE");

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    @GetMapping("/{sitterId}")
    public ResponseEntity<Map<String, Object>> getSubscription(@PathVariable UUID sitterId) {
        userRepository.findById(sitterId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到該用戶"));

        Subscription sub = subscriptionRepository.findBySitterId(sitterId).orElse(null);
        Map<String, Object> res = new HashMap<>();
        res.put("sitterId", sitterId);
        if (sub == null) {
            res.put("planTier", "FREE");
            res.put("expiredAt", null);
            res.put("monthlyOrderCount", 0);
        } else {
            res.put("planTier", sub.getPlanTier());
            res.put("expiredAt", sub.getExpiredAt() != null ? sub.getExpiredAt().toString() : null);
            res.put("monthlyOrderCount", sub.getMonthlyOrderCount());
        }
        return ResponseEntity.ok(res);
    }

    @PostMapping("/{sitterId}")
    public ResponseEntity<Map<String, Object>> setSubscription(
            @PathVariable UUID sitterId,
            @RequestBody Map<String, String> body) {

        String planTier = body.get("planTier");
        if (planTier == null || !VALID_TIERS.contains(planTier)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "planTier 必須為 FREE / BASIC / PRO / ULTIMATE");
        }

        User sitter = userRepository.findById(sitterId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到該保母"));

        OffsetDateTime expiredAt = null;
        String expiredAtStr = body.get("expiredAt");
        if (expiredAtStr != null && !expiredAtStr.isBlank()) {
            try {
                expiredAt = OffsetDateTime.parse(expiredAtStr);
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "expiredAt 格式錯誤，請使用 ISO-8601 (e.g. 2026-07-25T00:00:00Z)");
            }
        }

        Subscription sub = subscriptionRepository.findBySitterId(sitterId).orElse(null);
        if (sub == null) {
            sub = Subscription.builder()
                    .sitter(sitter)
                    .planTier(planTier)
                    .monthlyOrderCount(0)
                    .expiredAt(expiredAt)
                    .build();
        } else {
            sub.setPlanTier(planTier);
            sub.setExpiredAt(expiredAt);
        }
        subscriptionRepository.save(sub);

        UUID adminId = TokenContext.getUserId();
        auditLogService.writeUserActionLog(
                "ADMIN_SUBSCRIPTION_SET", "UPDATE", adminId, sub.getId(), "subscriptions");

        Map<String, Object> res = new HashMap<>();
        res.put("sitterId", sitterId);
        res.put("planTier", sub.getPlanTier());
        res.put("expiredAt", sub.getExpiredAt() != null ? sub.getExpiredAt().toString() : null);
        res.put("monthlyOrderCount", sub.getMonthlyOrderCount());
        return ResponseEntity.ok(res);
    }
}
