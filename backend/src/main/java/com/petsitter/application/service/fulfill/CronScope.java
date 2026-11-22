package com.petsitter.application.service.fulfill;

import java.util.UUID;

/**
 * 排程範圍（SD-ORDER §6.3 C1–C10、SD-FULFILL §2.3.1）：{@code orderId} 有值時只處理該測試訂單；
 * 無值（自動觸發）時排除 owner 或 sitter 為 {@code @e2e-journey.test} 的訂單。
 */
public record CronScope(UUID orderId) {

    public static final CronScope AUTO = new CronScope(null);

    public boolean targeted() {
        return orderId != null;
    }
}
