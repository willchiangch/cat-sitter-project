package com.catsitter.api.controller.v1;

import com.catsitter.api.dto.ProfileMiniDTO;
import com.catsitter.api.dto.SitterClientWhitelistDTO;
import com.catsitter.api.entity.Account;
import com.catsitter.api.service.WhitelistService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sitters/me/whitelist")
public class WhitelistController {

    private final WhitelistService whitelistService;

    public WhitelistController(WhitelistService whitelistService) {
        this.whitelistService = whitelistService;
    }

    @GetMapping
    public ResponseEntity<List<SitterClientWhitelistDTO>> getWhitelist(@AuthenticationPrincipal Account account) {
        return ResponseEntity.ok(whitelistService.getWhitelistedClients(account));
    }

    @PutMapping("/clients/{clientId}")
    public ResponseEntity<SitterClientWhitelistDTO> updateWhitelist(
            @AuthenticationPrincipal Account account,
            @PathVariable UUID clientId,
            @RequestParam Boolean skipQuestionnaire) {
        return ResponseEntity.ok(whitelistService.toggleSkipQuestionnaire(account, clientId, skipQuestionnaire));
    }

    @PostMapping("/clients")
    public ResponseEntity<SitterClientWhitelistDTO> addToWhitelist(
            @AuthenticationPrincipal Account account,
            @RequestBody Map<String, String> body) {
        UUID clientId = UUID.fromString(body.get("clientId"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(whitelistService.addToWhitelist(account, clientId));
    }

    @DeleteMapping("/clients/{clientId}")
    public ResponseEntity<Void> removeFromWhitelist(
            @AuthenticationPrincipal Account account,
            @PathVariable UUID clientId) {
        whitelistService.removeFromWhitelist(account, clientId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/search")
    public ResponseEntity<List<ProfileMiniDTO>> searchClients(
            @AuthenticationPrincipal Account account,
            @RequestParam String q) {
        return ResponseEntity.ok(whitelistService.searchClients(account, q));
    }
}
