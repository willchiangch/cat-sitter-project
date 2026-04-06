package com.catsitter.api.controller.v1;

import com.catsitter.api.dto.sitter.SitterSubscriptionDTO;
import com.catsitter.api.entity.Account;
import com.catsitter.api.service.SubscriptionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/sitters/me/subscription")
public class SitterSubscriptionController {

    private final SubscriptionService subscriptionService;

    public SitterSubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @GetMapping
    public ResponseEntity<SitterSubscriptionDTO> getCurrentSubscription(
            @AuthenticationPrincipal Account account) {
        return ResponseEntity.ok(subscriptionService.getCurrentSubscription(account));
    }

    @PutMapping
    public ResponseEntity<SitterSubscriptionDTO> changePlan(
            @AuthenticationPrincipal Account account,
            @RequestBody Map<String, String> body) {
        String planId = body.get("planId");
        return ResponseEntity.ok(subscriptionService.changePlan(account, planId));
    }

    @DeleteMapping
    public ResponseEntity<Void> cancelSubscription(
            @AuthenticationPrincipal Account account) {
        subscriptionService.cancelSubscription(account);
        return ResponseEntity.noContent().build();
    }
}
