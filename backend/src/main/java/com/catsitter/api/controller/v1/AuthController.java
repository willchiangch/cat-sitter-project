package com.catsitter.api.controller.v1;

import com.catsitter.api.dto.auth.*;
import com.catsitter.api.entity.Account;
import com.catsitter.api.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private final AuthService authService;
  private final com.catsitter.api.service.EmailVerificationService emailVerificationService;
  private final org.springframework.core.env.Environment env;

  public AuthController(
      AuthService authService, 
      com.catsitter.api.service.EmailVerificationService emailVerificationService,
      org.springframework.core.env.Environment env) {
    this.authService = authService;
    this.emailVerificationService = emailVerificationService;
    this.env = env;
  }

  @PostMapping("/register")
  public ResponseEntity<AuthTokenResponse> register(@Valid @RequestBody RegisterRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
  }

  @PostMapping("/login")
  public ResponseEntity<AuthTokenResponse> login(@Valid @RequestBody LoginRequest request) {
    return ResponseEntity.ok(authService.login(request));
  }

  @GetMapping("/me")
  public ResponseEntity<AuthMeResponse> getMe(@AuthenticationPrincipal Account account) {
    return ResponseEntity.ok(authService.getMe(account));
  }

  @PutMapping("/me/email")
  public ResponseEntity<AuthMeResponse> updateEmail(
          @AuthenticationPrincipal Account account,
          @Valid @RequestBody UpdateEmailRequest request) {
    System.err.println("\n[!] CONTROLLER: Received updateEmail request for email: " + request.email());
    return ResponseEntity.ok(authService.updateEmail(account, request.email()));
  }

  @PostMapping("/switch-role")
  public ResponseEntity<AuthMeResponse> switchRole(
          @AuthenticationPrincipal Account account,
          @Valid @RequestBody SwitchRoleRequest request) {
    return ResponseEntity.ok(authService.switchRole(account, request.roleType()));
  }

  @PostMapping("/complete-onboarding")
  public ResponseEntity<AuthMeResponse> completeOnboarding(
          @AuthenticationPrincipal Account account,
          @Valid @RequestBody CompleteOnboardingRequest request) {
    return ResponseEntity.ok(authService.completeOnboarding(account, request));
  }

  @PostMapping("/request-verification")
  public ResponseEntity<?> requestVerification(@AuthenticationPrincipal Account account) {
    System.out.println("[AUTH] Received request-verification for: " + (account != null ? account.getEmail() : "NULL"));
    String code = emailVerificationService.sendVerificationCode(account);
    
    // In smoke profile, return the code in body for frontend console logging
    if (java.util.Arrays.asList(env.getActiveProfiles()).contains("smoke")) {
        return ResponseEntity.ok(java.util.Map.of("debugCode", code));
    }
    
    return ResponseEntity.ok().build();
  }

  @PostMapping("/verify-email")
  public ResponseEntity<AuthMeResponse> verifyEmail(
          @AuthenticationPrincipal Account account,
          @Valid @RequestBody VerifyEmailRequest request) {
    boolean success = emailVerificationService.verifyCode(account, request.code());
    if (success) {
        return ResponseEntity.ok(authService.getMe(account));
    }
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
  }
}
