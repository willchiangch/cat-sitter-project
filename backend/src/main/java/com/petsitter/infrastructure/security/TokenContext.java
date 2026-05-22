package com.petsitter.infrastructure.security;

import java.util.UUID;

public class TokenContext {
    private static final ThreadLocal<UUID> userIdHolder = new ThreadLocal<>();

    public static void setUserId(UUID userId) {
        userIdHolder.set(userId);
    }

    public static UUID getUserId() {
        UUID id = userIdHolder.get();
        if (id == null) {
            // 提供一個 Dummy UUID 避免沒有 Mock 時噴錯 (僅供開發測試)
            return UUID.fromString("00000000-0000-0000-0000-000000000000");
        }
        return id;
    }

    public static void clear() {
        userIdHolder.remove();
    }
}
