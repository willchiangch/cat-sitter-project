package com.petsitter.infrastructure.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

import java.util.Optional;
import java.util.UUID;

/**
 * 目前請求的使用者身分（由 JwtAuthenticationFilter 從 JWT 的 userId claim 寫入）。
 * <p>
 * fix-batch12 J2：getUserId() 在沒有身分時一律拋 AuthenticationCredentialsNotFoundException
 * （GlobalExceptionHandler 的 AuthenticationException → 401），所有 profile 都一樣。
 * 過去回傳全 0 的 dummy UUID「僅供開發測試」，結果是忘了加 @PreAuthorize、或落在 permitAll 路徑
 * （/api/auth/**）的端點會靜默以 0000 身分執行，而不是失敗。本專案沒有獨立的 test profile
 * （測試跑 local），且全測試套件中只有兩支 fixture 漏設身分而依賴 dummy，已改正，
 * 因此不保留任何 profile 的 fallback。需要「可選身分」的匿名端點請用 tryGetUserId()。
 */
public class TokenContext {
    private static final ThreadLocal<UUID> userIdHolder = new ThreadLocal<>();

    public static void setUserId(UUID userId) {
        userIdHolder.set(userId);
    }

    public static UUID getUserId() {
        UUID id = userIdHolder.get();
        if (id == null) {
            throw new AuthenticationCredentialsNotFoundException("請先登入系統");
        }
        return id;
    }

    public static Optional<UUID> tryGetUserId() {
        return Optional.ofNullable(userIdHolder.get());
    }

    public static void clear() {
        userIdHolder.remove();
    }
}
