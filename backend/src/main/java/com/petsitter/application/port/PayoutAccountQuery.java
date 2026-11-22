package com.petsitter.application.port;

import java.util.Optional;
import java.util.UUID;

/**
 * 收款帳號查詢（SD-ORDER §2.3.2 / §2.4.2；SD-SUPPLY 實作，未重建前為過渡實作）。
 * {@link #getSnapshot} 單次讀取同一筆紀錄的四欄、不取鎖（PRD-025 BR-12）。
 */
public interface PayoutAccountQuery {

    boolean isConfigured(UUID sitterId);

    Optional<PayoutAccountSnapshot> getSnapshot(UUID sitterId);

    /** 四欄明文；{@code toString} 遮罩，不得落 log。 */
    record PayoutAccountSnapshot(String bankCode, String branch, String accountNumber, String accountName) {
        @Override
        public String toString() {
            return "PayoutAccountSnapshot[***]";
        }
    }
}
