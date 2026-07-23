package com.petsitter.interfaces.controller;

import com.petsitter.application.dto.AuthResponse;
import com.petsitter.application.dto.WebAuthnCredentialSummary;
import com.petsitter.application.dto.WebAuthnLoginOptionsRequest;
import com.petsitter.application.dto.WebAuthnLoginVerifyRequest;
import com.petsitter.application.dto.WebAuthnVerifyRequest;
import com.petsitter.application.service.WebAuthnService;
import com.petsitter.infrastructure.security.TokenContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PRD-000 AC-6：生物辨識登入 (WebAuthn)。路徑掛在 /api/auth/webauthn/**，
 * 沿用 SecurityConfig 既有的 /api/auth/** permitAll()；register/* 兩支端點的認證改由
 * TokenContext 手動把關（模式同 /api/auth/deactivate），未帶有效 Token 時
 * GlobalExceptionHandler.handleAccessDenied 會自動轉為 401。
 */
@RestController
@RequestMapping("/api/auth/webauthn")
@RequiredArgsConstructor
public class WebAuthnController {

    private final WebAuthnService webAuthnService;

    @PostMapping("/register/options")
    public ResponseEntity<String> startRegistration() {
        UUID userId = requireAuthenticatedUserId();
        String optionsJson = webAuthnService.startRegistration(userId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(optionsJson);
    }

    @PostMapping("/register/verify")
    public ResponseEntity<Map<String, String>> finishRegistration(@Valid @RequestBody WebAuthnVerifyRequest request) {
        UUID userId = requireAuthenticatedUserId();
        webAuthnService.finishRegistration(userId, request.getCredentialJson());
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "生物辨識裝置已成功註冊"));
    }

    @GetMapping("/credentials")
    public ResponseEntity<List<WebAuthnCredentialSummary>> listCredentials() {
        UUID userId = requireAuthenticatedUserId();
        List<WebAuthnCredentialSummary> summaries = webAuthnService.listCredentials(userId).stream()
                .map(credential -> WebAuthnCredentialSummary.builder()
                        .id(credential.getId())
                        .createdAt(credential.getCreatedAt())
                        .lastUsedAt(credential.getLastUsedAt())
                        .build())
                .toList();
        return ResponseEntity.ok(summaries);
    }

    @DeleteMapping("/credentials/{id}")
    public ResponseEntity<Map<String, String>> deleteCredential(@PathVariable UUID id) {
        UUID userId = requireAuthenticatedUserId();
        webAuthnService.deleteCredential(userId, id);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "已移除該生物辨識裝置"));
    }

    @PostMapping("/login/options")
    public ResponseEntity<String> startLogin(@Valid @RequestBody WebAuthnLoginOptionsRequest request) {
        String optionsJson = webAuthnService.startLogin(request.getEmail());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(optionsJson);
    }

    @PostMapping("/login/verify")
    public ResponseEntity<AuthResponse> finishLogin(@Valid @RequestBody WebAuthnLoginVerifyRequest request) {
        AuthResponse response = webAuthnService.finishLogin(request.getEmail(), request.getCredentialJson());
        return ResponseEntity.ok(response);
    }

    private UUID requireAuthenticatedUserId() {
        return TokenContext.tryGetUserId()
                .orElseThrow(() -> new AccessDeniedException("未認證的請求，請先登入"));
    }
}
