package com.catsitter.api.controller.v1;

import com.catsitter.api.dto.ProfileMiniDTO;
import com.catsitter.api.dto.SitterClientBlacklistDTO;
import com.catsitter.api.entity.Account;
import com.catsitter.api.service.BlacklistService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sitters/me/blacklist")
public class BlacklistController {

    private final BlacklistService blacklistService;

    public BlacklistController(BlacklistService blacklistService) {
        this.blacklistService = blacklistService;
    }

    @GetMapping
    public ResponseEntity<List<SitterClientBlacklistDTO>> getBlacklist(
            @AuthenticationPrincipal Account account) {
        return ResponseEntity.ok(blacklistService.getBlacklistedClients(account));
    }

    @PostMapping("/clients")
    public ResponseEntity<SitterClientBlacklistDTO> addToBlacklist(
            @AuthenticationPrincipal Account account,
            @RequestBody Map<String, String> body) {
        UUID clientId = UUID.fromString(body.get("clientId"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(blacklistService.addToBlacklist(account, clientId));
    }

    @DeleteMapping("/clients/{clientId}")
    public ResponseEntity<Void> removeFromBlacklist(
            @AuthenticationPrincipal Account account,
            @PathVariable UUID clientId) {
        blacklistService.removeFromBlacklist(account, clientId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/search")
    public ResponseEntity<List<ProfileMiniDTO>> searchClients(
            @AuthenticationPrincipal Account account,
            @RequestParam String q) {
        return ResponseEntity.ok(blacklistService.searchClients(account, q));
    }
}
