package com.catsitter.api.controller.dev;

import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.Profile;
import com.catsitter.api.entity.enums.RoleType;
import com.catsitter.api.repository.ProfileRepository;
import com.catsitter.api.repository.ProfileRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/dev")
@org.springframework.context.annotation.Profile({"dev", "smoke"})
public class DevToolsController {

    private final ProfileRepository profileRepository;

    public DevToolsController(ProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    @PostMapping("/sitters/me/verify")
    public ResponseEntity<?> verifyMe(
            @AuthenticationPrincipal Account account,
            @RequestBody Map<String, Boolean> body) {
        
        if (account == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        boolean verified = body.getOrDefault("verified", false);
        
        Profile profile = profileRepository.findByAccountIdAndRoleType(account.getId(), RoleType.SITTER)
                .orElseThrow(() -> new RuntimeException("Sitter profile not found for account: " + account.getId()));

        profile.setIsVerified(verified);
        profileRepository.save(profile);

        return ResponseEntity.ok(Map.of(
                "message", "Sitter verification status updated",
                "isVerified", profile.getIsVerified()
        ));
    }
}
