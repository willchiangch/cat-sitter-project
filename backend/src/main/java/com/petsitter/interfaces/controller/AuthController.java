package com.petsitter.interfaces.controller;

import com.petsitter.application.dto.AuthResponse;
import com.petsitter.application.dto.DeactivateAccountRequest;
import com.petsitter.application.dto.ForgotPasswordRequest;
import com.petsitter.application.dto.GoogleLoginRequest;
import com.petsitter.application.dto.GoogleLoginResponse;
import com.petsitter.application.dto.LoginRequest;
import com.petsitter.application.dto.RegisterRequest;
import com.petsitter.application.dto.RegisterResponse;
import com.petsitter.application.dto.ResendOtpRequest;
import com.petsitter.application.dto.ResetPasswordRequest;
import com.petsitter.application.dto.TokenRefreshRequest;
import com.petsitter.application.dto.SwitchRoleRequest;
import com.petsitter.application.dto.VerifyRegistrationOtpRequest;
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

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    @PostMapping("/register/verify-otp")
    public ResponseEntity<AuthResponse> verifyRegistrationOtp(@Valid @RequestBody VerifyRegistrationOtpRequest request) {
        return ResponseEntity.ok(authService.verifyRegistrationOtp(request));
    }

    @PostMapping("/register/resend-otp")
    public ResponseEntity<Map<String, String>> resendRegistrationOtp(@Valid @RequestBody ResendOtpRequest request) {
        authService.resendRegistrationOtp(request);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "驗證碼已重新寄送"));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refreshToken(@Valid @RequestBody TokenRefreshRequest request) {
        return ResponseEntity.ok(authService.refreshToken(request));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "若該信箱已註冊，重設密碼連結將寄送至該信箱"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "密碼已成功重設，請重新登入"));
    }

    @PostMapping("/switch-role")
    public ResponseEntity<AuthResponse> switchRole(@Valid @RequestBody SwitchRoleRequest request) {
        UUID userId = TokenContext.getUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("找不到使用者資訊"));
        return ResponseEntity.ok(authService.switchRole(user, request.getTargetRole()));
    }

    @PostMapping("/deactivate")
    public ResponseEntity<Map<String, String>> deactivate(@Valid @RequestBody DeactivateAccountRequest request) {
        UUID userId = TokenContext.getUserId();
        authService.deactivateAccount(userId, request.getPassword());
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "帳號已成功註銷"));
    }

    @PostMapping("/logout-all-devices")
    public ResponseEntity<Map<String, String>> logoutAllDevices() {
        UUID userId = TokenContext.getUserId();
        authService.logoutAllDevices(userId);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "已登出所有裝置"));
    }

    @PostMapping("/google")
    public ResponseEntity<GoogleLoginResponse> loginWithGoogle(@Valid @RequestBody GoogleLoginRequest request) {
        return ResponseEntity.ok(authService.loginWithGoogle(request));
    }
}
