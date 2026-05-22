package com.petsitter.application.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, String>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "AUTH_FAILED", "message", "帳號或密碼錯誤"));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, String>> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "UNAUTHORIZED", "message", "認證失敗: " + ex.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage() : "";
        if (msg.toLowerCase().contains("idempotency")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "DUPLICATE_REQUEST", "message", "系統已受理此請求，請勿重複送單"));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "DATA_ERROR", "message", "資料處理異常"));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(org.springframework.security.access.AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "FORBIDDEN", "message", "權限不足"));
    }

    @ExceptionHandler(CapacityFullException.class)
    public ResponseEntity<Map<String, String>> handleCapacityFull(CapacityFullException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "CAPACITY_FULL", "message", ex.getMessage()));
    }

    // --- SD-006 SaaS Gating ---
    @ExceptionHandler(AuthPlanLimitException.class)
    public ResponseEntity<Map<String, String>> handleAuthPlanLimit(AuthPlanLimitException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "AUTH_PLAN_LIMIT", "message", ex.getMessage()));
    }

    // --- SD-006 Zero-Trust Pricing ---
    @ExceptionHandler(PricingMismatchException.class)
    public ResponseEntity<Map<String, String>> handlePricingMismatch(PricingMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "PRICING_MISMATCH", "message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "INVALID_PARAMETER", "message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "STATE_CONFLICT", "message", ex.getMessage()));
    }
}
