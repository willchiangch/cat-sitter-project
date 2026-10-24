package com.petsitter.interfaces.controller;

import com.petsitter.domain.model.Subscription;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.SubscriptionRepository;
import com.petsitter.domain.repository.UserRepository;
import com.petsitter.infrastructure.security.TokenContext;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 本機 / 非正式環境專用：保母自助切換訂閱等級（開發與 demo 用）。
 *
 * <p>正式站（SPRING_PROFILES_ACTIVE=prod）不註冊這個 bean，端點不存在（404）。
 * 過去這支端點掛在 GatekeeperController，任何已登入使用者都能把自己升到 PRO / ULTIMATE，
 * 繞過 PlanGatingAspect 的付費門檻。正式站開通一律走 {@link AdminSubscriptionController}
 * （hasRole ADMIN、寫 ADMIN_SUBSCRIPTION_SET 審計），線上訂閱付款待 SD-015。
 */
@RestController
@Profile("!prod")
@RequestMapping("/api/sitter/gatekeeper")
@RequiredArgsConstructor
public class GatekeeperDevSubscriptionController {

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;

    @PostMapping("/subscription/mock")
    public ResponseEntity<Map<String, Object>> mockUpdateSubscription(
            @RequestBody Map<String, String> body) {
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

        Map<String, Object> res = new HashMap<>();
        res.put("planTier", sub.getPlanTier());
        res.put("expiredAt", null);
        return ResponseEntity.ok(res);
    }
}
