package com.catsitter.api.controller.v1;

import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.SitterClientWhitelist;
import com.catsitter.api.service.WhitelistService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sitters/me/whitelist")
public class WhitelistController {

    private final WhitelistService whitelistService;

    public WhitelistController(WhitelistService whitelistService) {
        this.whitelistService = whitelistService;
    }

    @GetMapping
    public ResponseEntity<List<SitterClientWhitelist>> getWhitelist(@AuthenticationPrincipal Account account) {
        return ResponseEntity.ok(whitelistService.getWhitelistedClients(account));
    }

    @PutMapping("/clients/{clientId}")
    public ResponseEntity<SitterClientWhitelist> updateWhitelist(
            @AuthenticationPrincipal Account account,
            @PathVariable UUID clientId,
            @RequestParam Boolean skipQuestionnaire) {
        return ResponseEntity.ok(whitelistService.toggleSkipQuestionnaire(account, clientId, skipQuestionnaire));
    }

    @DeleteMapping("/clients/{clientId}")
    public ResponseEntity<Void> removeFromWhitelist(
            @AuthenticationPrincipal Account account,
            @PathVariable UUID clientId) {
        whitelistService.removeFromWhitelist(account, clientId);
        return ResponseEntity.noContent().build();
    }
}
