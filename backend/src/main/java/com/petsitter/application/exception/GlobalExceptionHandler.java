package com.petsitter.application.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Map;

/**
 * 全域例外對映（SD-GLOBAL-SPEC §2.6、SD-ORDER §6.4 全域表）。回應一律為 {@link ErrorResponse} 格式。
 * <p>
 * 未重建模組的例外碼維持現行對映（只換格式：舊 {@code error} 欄位改為 {@code code}）；重建模組的鎖 / 完整性對映
 * 由各模組 advice 繼承 {@link AbstractModuleExceptionHandler} 處理。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ------------------------------------------------------------------
    // 認證 / 授權（SD-ORDER §6.4 全域表）
    // ------------------------------------------------------------------

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex) {
        return ApiErrors.of(HttpStatus.UNAUTHORIZED, "AUTH_FAILED", "帳號或密碼錯誤");
    }

    // 故意用 429 而非 401：避免撞上前端 axiosClient 全域的 refresh-token 靜默重試機制 (PRD-000 AC-4)
    @ExceptionHandler(LockedException.class)
    public ResponseEntity<ErrorResponse> handleLocked(LockedException ex) {
        return ApiErrors.of(HttpStatus.TOO_MANY_REQUESTS, "ACCOUNT_LOCKED", ex.getMessage());
    }

    /** {@code TokenContext.getUserId()} 無身分（GLOBAL §3.4）→ 401 {@code UNAUTHENTICATED}。 */
    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCredentialsNotFound(AuthenticationCredentialsNotFoundException ex) {
        return ApiErrors.of(CommonErrorCode.UNAUTHENTICATED);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex) {
        return ApiErrors.of(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "認證失敗: " + ex.getMessage());
    }

    /** {@code @PreAuthorize} 拒絕 → 403 {@code ROLE_MISMATCH}（CQ-42）；未登入 → 401 {@code UNAUTHENTICATED}。 */
    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAuthorizationDenied(AuthorizationDeniedException ex) {
        if (isAnonymous()) {
            return ApiErrors.of(CommonErrorCode.UNAUTHENTICATED);
        }
        return ApiErrors.of(CommonErrorCode.ROLE_MISMATCH);
    }

    /** 其他 {@code AccessDeniedException}（Service 歸屬檢查）→ 403 {@code FORBIDDEN}；未登入 → 401。 */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(org.springframework.security.access.AccessDeniedException ex) {
        if (isAnonymous()) {
            return ApiErrors.of(CommonErrorCode.UNAUTHENTICATED);
        }
        return ApiErrors.of(CommonErrorCode.FORBIDDEN);
    }

    private static boolean isAnonymous() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth instanceof AnonymousAuthenticationToken || !auth.isAuthenticated();
    }

    // ------------------------------------------------------------------
    // 進方法前的格式錯誤 → 400 VALIDATION_FAILED（SD-ORDER §6.4 全域表）
    // ------------------------------------------------------------------

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        return ApiErrors.of(CommonErrorCode.VALIDATION_FAILED, Map.of("field", "body"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ApiErrors.of(CommonErrorCode.VALIDATION_FAILED, Map.of("field", ex.getName()));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPart(MissingServletRequestPartException ex) {
        return ApiErrors.of(CommonErrorCode.VALIDATION_FAILED, Map.of("field", ex.getRequestPartName()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex) {
        return ApiErrors.of(CommonErrorCode.VALIDATION_FAILED, Map.of("field", ex.getParameterName()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return ApiErrors.of(CommonErrorCode.VALIDATION_FAILED, Map.of("field", "Content-Type"));
    }

    /**
     * 上傳端點收到非 multipart 請求、multipart 解析失敗或超過容器上限（含 {@code MaxUploadSizeExceededException}）。
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMultipart(MultipartException ex) {
        return ApiErrors.of(CommonErrorCode.VALIDATION_FAILED, Map.of("field", "file"));
    }

    // ------------------------------------------------------------------
    // 共用機制（GLOBAL §2.4 冪等、§2.3 鎖、GCS）
    // ------------------------------------------------------------------

    @ExceptionHandler(RequestValidationException.class)
    public ResponseEntity<ErrorResponse> handleRequestValidation(RequestValidationException ex) {
        return ApiErrors.of(CommonErrorCode.VALIDATION_FAILED, ex.getPayload());
    }

    @ExceptionHandler(IdempotencyException.class)
    public ResponseEntity<ErrorResponse> handleIdempotency(IdempotencyException ex) {
        return ApiErrors.of(ex.getKind().code());
    }

    @ExceptionHandler(OrderBusyException.class)
    public ResponseEntity<ErrorResponse> handleOrderBusy(OrderBusyException ex) {
        return ApiErrors.orderBusy();
    }

    @ExceptionHandler(StorageUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleStorageUnavailable(StorageUnavailableException ex) {
        log.error("storage unavailable", ex);
        return ApiErrors.of(CommonErrorCode.STORAGE_UNAVAILABLE);
    }

    // ------------------------------------------------------------------
    // 未重建模組的現行對映（只換格式）
    // ------------------------------------------------------------------

    /**
     * 舊冪等（{@code LegacyIdempotencyService} / {@code idempotency_keys}）的重複送單。
     * 依 GLOBAL §2.6：只有 SQLSTATE 23505 的列名約束可對映 409；其餘維持未重建模組現行的 400 {@code DATA_ERROR}。
     * 舊訂單約束 {@code idx_orders_payment_idempotency}、{@code idx_mod_req_supplement_idempotency} 已隨
     * V20261122_01 刪除，對映一併移除（SD-ORDER §6.4）。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        PersistenceErrorClassifier.Classification c = PersistenceErrorClassifier.classify(ex);
        String constraint = c.constraintName();
        if (c.kind() == PersistenceErrorClassifier.Kind.UNIQUE_VIOLATION && constraint != null
                && "pk_idempotency".equalsIgnoreCase(constraint)) {
            return duplicateRequest();
        }
        // 舊程式先查再拋（無 SQLSTATE）：LegacyIdempotencyService.checkAndConsume
        String msg = ex.getMessage() != null ? ex.getMessage() : "";
        if (c.sqlState() == null && msg.contains("Duplicate idempotency key")) {
            return duplicateRequest();
        }
        return ApiErrors.of(HttpStatus.BAD_REQUEST, "DATA_ERROR", "資料處理異常");
    }

    private static ResponseEntity<ErrorResponse> duplicateRequest() {
        return ApiErrors.of(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", "系統已受理此請求，請勿重複送單");
    }

    // --- SD-006 SaaS Gating ---
    @ExceptionHandler(AuthPlanLimitException.class)
    public ResponseEntity<ErrorResponse> handleAuthPlanLimit(AuthPlanLimitException ex) {
        return ApiErrors.of(HttpStatus.FORBIDDEN, "AUTH_PLAN_LIMIT", ex.getMessage());
    }

    // --- SD-006 Zero-Trust Pricing ---
    @ExceptionHandler(PricingMismatchException.class)
    public ResponseEntity<ErrorResponse> handlePricingMismatch(PricingMismatchException ex) {
        return ApiErrors.of(HttpStatus.BAD_REQUEST, "PRICING_MISMATCH", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ApiErrors.of(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
        return ApiErrors.of(HttpStatus.CONFLICT, "STATE_CONFLICT", ex.getMessage());
    }

    @ExceptionHandler(VisitReportException.class)
    public ResponseEntity<ErrorResponse> handleVisitReport(VisitReportException ex) {
        return ApiErrors.of(ex.getStatus(), ex.getError(), ex.getMessage());
    }

    @ExceptionHandler(KycException.class)
    public ResponseEntity<ErrorResponse> handleKycException(KycException ex) {
        return ApiErrors.of(ex.getStatus(), ex.getError(), ex.getMessage());
    }

    // --- SD-003 自訂服務方案例外攔截 ---
    @ExceptionHandler(ServicePlanException.class)
    public ResponseEntity<ErrorResponse> handleServicePlan(ServicePlanException ex) {
        return ApiErrors.of(ex.getStatus(), ex.getError(), ex.getMessage());
    }

    // --- PRD-004 事前問卷例外攔截 ---
    @ExceptionHandler(SitterQuestionException.class)
    public ResponseEntity<ErrorResponse> handleSitterQuestion(SitterQuestionException ex) {
        return ApiErrors.of(ex.getStatus(), ex.getError(), ex.getMessage());
    }

    // --- PRD-019 我的最愛保母例外攔截 ---
    @ExceptionHandler(FavoriteSitterException.class)
    public ResponseEntity<ErrorResponse> handleFavoriteSitter(FavoriteSitterException ex) {
        return ApiErrors.of(ex.getStatus(), ex.getError(), ex.getMessage());
    }

    // --- PRD-010 信任圈與轉介例外攔截 ---
    @ExceptionHandler(TrustCircleException.class)
    public ResponseEntity<ErrorResponse> handleTrustCircle(TrustCircleException ex) {
        return ApiErrors.of(ex.getStatus(), ex.getError(), ex.getMessage());
    }

    // 樂觀鎖衝突防護（未重建模組現行對映：409 MSG_DATA_CONCURRENCY_CONFLICT；重建模組由模組 advice 對映 ORDER_BUSY）
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
        return ApiErrors.of(HttpStatus.CONFLICT, "MSG_DATA_CONCURRENCY_CONFLICT", "內容已被更新，請重新整理後再試");
    }

    // 處理 Bean Validation 校驗異常（未重建模組現行對映：400 INVALID_PARAMETER）
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(org.springframework.web.bind.MethodArgumentNotValidException ex) {
        String defaultMessage = ex.getBindingResult().getFieldErrors().stream()
                .map(org.springframework.validation.FieldError::getDefaultMessage)
                .findFirst()
                .orElse("參數欄位校驗失敗");
        return ApiErrors.of(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", defaultMessage);
    }

    @ExceptionHandler(NotificationException.class)
    public ResponseEntity<ErrorResponse> handleNotification(NotificationException ex) {
        return ApiErrors.of(ex.getStatus(), ex.getError(), ex.getMessage());
    }

    @ExceptionHandler(PublicProfileException.class)
    public ResponseEntity<ErrorResponse> handlePublicProfile(PublicProfileException ex) {
        return ApiErrors.of(ex.getStatus(), ex.getError(), ex.getMessage());
    }

    // --- PRD-000 Email OTP 註冊驗證例外攔截 ---
    @ExceptionHandler(RegistrationException.class)
    public ResponseEntity<ErrorResponse> handleRegistration(RegistrationException ex) {
        return ApiErrors.of(ex.getStatus(), ex.getError(), ex.getMessage());
    }

    // --- PRD-000 AC-8 帳號註銷例外攔截 ---
    @ExceptionHandler(AccountDeactivationException.class)
    public ResponseEntity<ErrorResponse> handleAccountDeactivation(AccountDeactivationException ex) {
        return ApiErrors.of(ex.getStatus(), ex.getError(), ex.getMessage());
    }

    // --- PRD-000 AC-5 Google 第三方登入例外攔截 ---
    @ExceptionHandler(GoogleAuthException.class)
    public ResponseEntity<ErrorResponse> handleGoogleAuth(GoogleAuthException ex) {
        return ApiErrors.of(ex.getStatus(), ex.getError(), ex.getMessage());
    }

    // --- PRD-000 AC-6 生物辨識登入 (WebAuthn) 例外攔截 ---
    @ExceptionHandler(WebAuthnException.class)
    public ResponseEntity<ErrorResponse> handleWebAuthn(WebAuthnException ex) {
        return ApiErrors.of(ex.getStatus(), ex.getError(), ex.getMessage());
    }

    // --- SD-000 Refresh Token 例外攔截 ---
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ErrorResponse> handleAuth(AuthException ex) {
        return ApiErrors.of(ex.getStatus(), ex.getError(), ex.getMessage());
    }

    // ------------------------------------------------------------------
    // 未處理例外 → 500 INTERNAL_ERROR（不含堆疊）
    // ------------------------------------------------------------------

    /**
     * Spring MVC 自身帶狀態碼的例外（{@link org.springframework.web.ErrorResponse}：404 無此路由、405、406…）
     * 保留原狀態碼；其餘一律 500 {@code INTERNAL_ERROR}。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        if (ex instanceof org.springframework.web.ErrorResponse springError) {
            HttpStatusCode status = springError.getStatusCode();
            if (status.value() == HttpStatus.NOT_FOUND.value()) {
                return ApiErrors.of(CommonErrorCode.NOT_FOUND);
            }
            if (status.is4xxClientError()) {
                HttpStatus resolved = HttpStatus.resolve(status.value());
                return ApiErrors.of(status, resolved != null ? resolved.name() : "HTTP_" + status.value(),
                        resolved != null ? resolved.getReasonPhrase() : "請求錯誤");
            }
        }
        log.error("unhandled exception mapped to INTERNAL_ERROR", PersistenceErrorClassifier.sanitized(ex));
        return ApiErrors.of(CommonErrorCode.INTERNAL_ERROR);
    }
}
