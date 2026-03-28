package com.catsitter.api.controller.v1;

import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.SubscriptionPlan;
import com.catsitter.api.service.SubscriptionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("/plans")
    public ResponseEntity<List<SubscriptionPlan>> getPlans() {
        return ResponseEntity.ok(subscriptionService.getAllPlans());
    }

    @PostMapping("/checkout")
    public ResponseEntity<Map<String, String>> checkout(
            @AuthenticationPrincipal Account account,
            @RequestBody Map<String, String> payload) {
        
        String planName = payload.get("planName");
        String promoCode = payload.get("promoCode");
        
        Map<String, String> uppParams = subscriptionService.createCheckoutParams(account, planName, promoCode);
        return ResponseEntity.ok(uppParams);
    }
}
