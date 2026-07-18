package com.petsitter.interfaces.controller;

import com.petsitter.application.dto.CreateReferralRequest;
import com.petsitter.application.dto.ReferralCandidateDto;
import com.petsitter.application.dto.SitterSearchResultDto;
import com.petsitter.application.dto.TrustRelationshipDto;
import com.petsitter.application.service.ReferralService;
import com.petsitter.application.service.TrustCircleService;
import com.petsitter.infrastructure.security.TokenContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/sitter/trust-circle")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SITTER')")
public class TrustCircleController {

    private final TrustCircleService trustCircleService;
    private final ReferralService referralService;

    @GetMapping
    public ResponseEntity<List<TrustRelationshipDto>> listTrustCircle() {
        return ResponseEntity.ok(trustCircleService.listTrustCircle(TokenContext.getUserId()));
    }

    @GetMapping("/search")
    public ResponseEntity<SitterSearchResultDto> searchSitter(@RequestParam String query) {
        return ResponseEntity.ok(trustCircleService.searchSitter(TokenContext.getUserId(), query));
    }

    @GetMapping("/requests/incoming")
    public ResponseEntity<List<TrustRelationshipDto>> listIncomingRequests() {
        return ResponseEntity.ok(trustCircleService.listIncomingRequests(TokenContext.getUserId()));
    }

    @GetMapping("/requests/outgoing")
    public ResponseEntity<List<TrustRelationshipDto>> listOutgoingRequests() {
        return ResponseEntity.ok(trustCircleService.listOutgoingRequests(TokenContext.getUserId()));
    }

    @PostMapping("/requests/{targetId}")
    public ResponseEntity<Map<String, String>> sendTrustRequest(@PathVariable UUID targetId) {
        trustCircleService.sendTrustRequest(TokenContext.getUserId(), targetId);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "已送出信任圈邀請"));
    }

    @PostMapping("/requests/{relationshipId}/respond")
    public ResponseEntity<Map<String, String>> respondToTrustRequest(
            @PathVariable UUID relationshipId,
            @RequestBody Map<String, Boolean> body) {
        boolean accept = Boolean.TRUE.equals(body.get("accept"));
        trustCircleService.respondToTrustRequest(TokenContext.getUserId(), relationshipId, accept);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", accept ? "已加入信任圈" : "已拒絕邀請"));
    }

    @DeleteMapping("/{relationshipId}")
    public ResponseEntity<Map<String, String>> removeTrustRelationship(@PathVariable UUID relationshipId) {
        trustCircleService.removeTrustRelationship(TokenContext.getUserId(), relationshipId);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "已移除信任關係"));
    }

    @GetMapping("/referral-candidates")
    public ResponseEntity<List<ReferralCandidateDto>> getReferralCandidates(@RequestParam UUID ownerId) {
        return ResponseEntity.ok(referralService.getReferralCandidates(TokenContext.getUserId(), ownerId));
    }

    @PostMapping("/referrals")
    public ResponseEntity<Map<String, String>> createReferral(@RequestBody CreateReferralRequest request) {
        referralService.createReferral(TokenContext.getUserId(), request);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "轉介已送出"));
    }
}
