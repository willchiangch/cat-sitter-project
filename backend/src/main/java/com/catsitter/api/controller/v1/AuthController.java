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

  public AuthController(AuthService authService) {
    this.authService = authService;
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

  @PostMapping("/switch-role")
  public ResponseEntity<AuthMeResponse> switchRole(
          @AuthenticationPrincipal Account account,
          @Valid @RequestBody SwitchRoleRequest request) {
    return ResponseEntity.ok(authService.switchRole(account, request.roleType()));
  }
}
