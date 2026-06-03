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
        Throwable rootCause = org.springframework.core.NestedExceptionUtils.getRootCause(ex);
        if (rootCause != null && "org.postgresql.util.PSQLException".equals(rootCause.getClass().getName())) {
            try {
                java.lang.reflect.Method getServerErrorMessageMethod = rootCause.getClass().getMethod("getServerErrorMessage");
                Object serverErrorMessage = getServerErrorMessageMethod.invoke(rootCause);
                if (serverErrorMessage != null) {
                    java.lang.reflect.Method getConstraintMethod = serverErrorMessage.getClass().getMethod("getConstraint");
                    String constraint = (String) getConstraintMethod.invoke(serverErrorMessage);
                    if ("idx_orders_payment_idempotency".equalsIgnoreCase(constraint)) {
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(Map.of("error", "DUPLICATE_REQUEST", "message", "系統已受理此請求，請勿重複送單"));
                    }
                }
            } catch (Exception ignored) {
                // Ignore exception and fall back to message check
            }
        }
        
        String msg = ex.getMessage() != null ? ex.getMessage() : "";
        if (msg.contains("idx_orders_payment_idempotency")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "DUPLICATE_REQUEST", "message", "系統已受理此請求，請勿重複送單"));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "DATA_ERROR", "message", "資料處理異常"));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(org.springframework.security.access.AccessDeniedException ex) {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "UNAUTHORIZED", "message", "未認證的請求，請先登入"));
        }
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

    @ExceptionHandler(VisitReportException.class)
    public ResponseEntity<Map<String, String>> handleVisitReport(VisitReportException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(Map.of("error", ex.getError(), "message", ex.getMessage()));
    }

    // --- SD-003 自訂服務方案例外攔截 ---
    @ExceptionHandler(ServicePlanException.class)
    public ResponseEntity<Map<String, String>> handleServicePlan(ServicePlanException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(Map.of("error", ex.getError(), "message", ex.getMessage()));
    }

    // 樂觀鎖衝突防護 (409 VERSION_CONFLICT)
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> handleOptimisticLock(org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "VERSION_CONFLICT", "message", "內容已被更新，請重新整理後再試"));
    }

    // 處理 Bean Validation 校驗異常 (400 INVALID_PARAMETER)
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(org.springframework.web.bind.MethodArgumentNotValidException ex) {
        String defaultMessage = ex.getBindingResult().getFieldErrors().stream()
                .map(org.springframework.validation.FieldError::getDefaultMessage)
                .findFirst()
                .orElse("參數欄位校驗失敗");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "INVALID_PARAMETER", "message", defaultMessage));
    }
}
