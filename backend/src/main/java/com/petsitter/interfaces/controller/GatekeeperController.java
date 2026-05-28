package com.petsitter.interfaces.controller;

import com.petsitter.application.dto.AddRuleRequest;
import com.petsitter.application.dto.GatekeeperRuleDto;
import com.petsitter.application.service.GatekeeperService;
import com.petsitter.domain.model.GatekeeperRule;
import com.petsitter.domain.model.Subscription;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.SubscriptionRepository;
import com.petsitter.domain.repository.UserRepository;
import com.petsitter.infrastructure.security.TokenContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/sitter/gatekeeper")
@RequiredArgsConstructor
public class GatekeeperController {

    private final GatekeeperService gatekeeperService;
    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;

    @GetMapping("/subscription")
    public ResponseEntity<java.util.Map<String, Object>> getSubscription() {
        UUID sitterId = TokenContext.getUserId();
        Subscription sub = subscriptionRepository.findBySitterId(sitterId).orElse(null);
        java.util.Map<String, Object> res = new java.util.HashMap<>();
        if (sub == null) {
            res.put("planTier", "FREE");
            res.put("expiredAt", null);
        } else {
            res.put("planTier", sub.getPlanTier());
            res.put("expiredAt", sub.getExpiredAt() != null ? sub.getExpiredAt().toString() : null);
        }
        return ResponseEntity.ok(res);
    }

    @PostMapping("/subscription/mock")
    public ResponseEntity<java.util.Map<String, Object>> mockUpdateSubscription(
            @RequestBody java.util.Map<String, String> body) {
        UUID sitterId = TokenContext.getUserId();
        String tier = body.get("planTier");
        if (tier == null) {
            tier = "FREE";
        }
        
        Subscription sub = subscriptionRepository.findBySitterId(sitterId).orElse(null);
        if (sub == null) {
            User sitter = userRepository.findById(sitterId).orElseThrow();
            sub = Subscription.builder()
                    .sitter(sitter)
                    .planTier(tier)
                    .monthlyOrderCount(0)
                    .build();
        } else {
            sub.setPlanTier(tier);
            sub.setExpiredAt(null);
        }
        subscriptionRepository.save(sub);
        
        java.util.Map<String, Object> res = new java.util.HashMap<>();
        res.put("planTier", sub.getPlanTier());
        res.put("expiredAt", null);
        return ResponseEntity.ok(res);
    }

    @GetMapping
    public ResponseEntity<List<GatekeeperRuleDto>> getRules() {
        UUID sitterId = TokenContext.getUserId();
        List<GatekeeperRule> rules = gatekeeperService.getRules(sitterId);
        List<GatekeeperRuleDto> dtos = rules.stream().map(this::toDto).collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @PostMapping
    public ResponseEntity<GatekeeperRuleDto> addRule(@Valid @RequestBody AddRuleRequest request) {
        UUID sitterId = TokenContext.getUserId();

        // SaaS 訂閱鎖定 (Controller 層標註/檢查)
        Subscription sub = subscriptionRepository.findBySitterId(sitterId)
                .orElseThrow(() -> new AccessDeniedException("權限不足，請先訂閱方案"));

        boolean isPro = "PRO".equals(sub.getPlanTier()) || "ULTIMATE".equals(sub.getPlanTier());
        boolean isUltimate = "ULTIMATE".equals(sub.getPlanTier());
        boolean isExpired = sub.getExpiredAt() != null && sub.getExpiredAt().isBefore(OffsetDateTime.now());

        if (isExpired || (!isPro && !isUltimate)) {
            throw new AccessDeniedException("此為專業版/頂級版功能，請升級方案");
        }

        if ("PRO".equals(sub.getPlanTier()) && !"BLACK".equals(request.getRuleType())) {
            throw new AccessDeniedException("專業版僅開放設定黑名單功能");
        }

        GatekeeperRule savedRule = gatekeeperService.addRule(
                sitterId,
                request.getTargetEmail(),
                request.getRuleType(),
                request.getScopeType(),
                request.getPlanId()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(savedRule));
    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Void> deleteRule(@PathVariable UUID ruleId) {
        UUID sitterId = TokenContext.getUserId();
        gatekeeperService.deleteRule(sitterId, ruleId);
        return ResponseEntity.noContent().build();
    }

    private GatekeeperRuleDto toDto(GatekeeperRule rule) {
        User targetUser = userRepository.findById(rule.getTargetUserId()).orElse(null);
        String email = targetUser != null ? targetUser.getEmail() : null;
        return GatekeeperRuleDto.builder()
                .id(rule.getId())
                .sitterId(rule.getSitterId())
                .ruleType(rule.getRuleType())
                .scopeType(rule.getScopeType())
                .planId(rule.getPlanId())
                .targetUserId(rule.getTargetUserId())
                .targetEmail(maskEmail(email))
                .build();
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return email;
        }
        String[] parts = email.split("@");
        String local = parts[0];
        String domain = parts[1];
        if (local.length() <= 2) {
            return local + "***@" + domain;
        } else {
            return local.substring(0, 2) + "***@" + domain;
        }
    }
}
