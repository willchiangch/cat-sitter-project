package com.catsitter.api.controller.v1;

import com.catsitter.api.dto.sitter.SitterProfileResponse;
import com.catsitter.api.dto.sitter.UpdateSitterProfileRequest;
import com.catsitter.api.entity.Account;
import com.catsitter.api.service.SitterProfileService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sitters/me/profile")
public class SitterProfileController {

    private final SitterProfileService sitterProfileService;

    public SitterProfileController(SitterProfileService sitterProfileService) {
        this.sitterProfileService = sitterProfileService;
    }

    @GetMapping
    public ResponseEntity<SitterProfileResponse> getProfile(@AuthenticationPrincipal Account account) {
        return ResponseEntity.ok(sitterProfileService.getSitterProfile(account));
    }

    @PutMapping
    public ResponseEntity<SitterProfileResponse> updateProfile(
            @AuthenticationPrincipal Account account,
            @Valid @RequestBody UpdateSitterProfileRequest request) {
        return ResponseEntity.ok(sitterProfileService.updateSitterProfile(account, request));
    }
}
