package com.petsitter.interfaces.controller;

import com.petsitter.application.dto.AuthResponse;
import com.petsitter.application.dto.LoginRequest;
import com.petsitter.application.dto.RegisterRequest;
import com.petsitter.application.dto.TokenRefreshRequest;
import com.petsitter.application.dto.SwitchRoleRequest;
import com.petsitter.application.service.AuthService;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.UserRepository;
import com.petsitter.infrastructure.security.TokenContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refreshToken(@Valid @RequestBody TokenRefreshRequest request) {
        return ResponseEntity.ok(authService.refreshToken(request));
    }

    @PostMapping("/switch-role")
    public ResponseEntity<AuthResponse> switchRole(@Valid @RequestBody SwitchRoleRequest request) {
        UUID userId = TokenContext.getUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("找不到使用者資訊"));
        return ResponseEntity.ok(authService.switchRole(user, request.getTargetRole()));
    }
}
