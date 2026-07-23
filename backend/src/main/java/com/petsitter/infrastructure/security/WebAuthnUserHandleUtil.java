package com.petsitter.infrastructure.security;

import com.yubico.webauthn.data.ByteArray;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * WebAuthn 的 user handle 是不透明 byte 陣列，此處直接用 UUID 的 16 bytes 表示，
 * 避免額外儲存一份對照表。
 */
public final class WebAuthnUserHandleUtil {

    private WebAuthnUserHandleUtil() {
    }

    public static ByteArray toUserHandle(UUID userId) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(userId.getMostSignificantBits());
        buffer.putLong(userId.getLeastSignificantBits());
        return new ByteArray(buffer.array());
    }

    public static UUID toUserId(ByteArray userHandle) {
        ByteBuffer buffer = ByteBuffer.wrap(userHandle.getBytes());
        long mostSigBits = buffer.getLong();
        long leastSigBits = buffer.getLong();
        return new UUID(mostSigBits, leastSigBits);
    }
}
