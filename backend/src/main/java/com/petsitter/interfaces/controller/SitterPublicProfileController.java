package com.petsitter.interfaces.controller;

import com.petsitter.application.dto.PublicProfileResponse;
import com.petsitter.application.dto.UpdatePublicProfileRequest;
import com.petsitter.application.service.SitterPublicProfileService;
import com.petsitter.infrastructure.security.TokenContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/sitter/profile")
@RequiredArgsConstructor
public class SitterPublicProfileController {

    private final SitterPublicProfileService publicProfileService;

    /**
     * 更新保母公開檔案 (Sitter)
     */
    @PreAuthorize("hasRole('SITTER')")
    @PutMapping
    public ResponseEntity<Map<String, Object>> updateProfile(
            @Valid @RequestBody UpdatePublicProfileRequest request) {
        
        UUID sitterId = TokenContext.getUserId();
        log.info("Updating public profile for sitter: {}", sitterId);
        publicProfileService.updateProfile(sitterId, request);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "公開檔案更新成功"));
    }

    /**
     * 上傳保母頭像 (Sitter)
     */
    @PreAuthorize("hasRole('SITTER')")
    @PostMapping("/avatar")
    public ResponseEntity<Map<String, Object>> uploadAvatar(
            @RequestParam("file") MultipartFile file) {
        
        UUID sitterId = TokenContext.getUserId();
        log.info("Uploading avatar for sitter: {}", sitterId);
        String avatarUrl = publicProfileService.uploadAvatar(sitterId, file);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "avatarUrl", avatarUrl));
    }

    /**
     * 取得保母公開檔案 (所有人，支援匿名與 Gating)
     */
    @GetMapping("/{sitterId}")
    public ResponseEntity<PublicProfileResponse> getPublicProfile(
            @PathVariable("sitterId") UUID sitterId) {
        
        Optional<UUID> currentUserIdOpt = TokenContext.tryGetUserId();
        log.info("Getting public profile for sitter: {}, current user: {}", sitterId, currentUserIdOpt.orElse(null));
        PublicProfileResponse response = publicProfileService.getPublicProfile(sitterId, currentUserIdOpt);
        return ResponseEntity.ok(response);
    }
}
