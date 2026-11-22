package com.petsitter.application.exception;

import java.time.Instant;
import java.util.Map;

/**
 * 全域錯誤回應格式（SD-GLOBAL-SPEC §2.6、SD-ORDER §6.1）：{@code { code, message, payload, traceId, timestamp }}。
 * 值為 null 的欄位不輸出（{@code spring.jackson.default-property-inclusion: non-null}）。
 */
public record ErrorResponse(
        String code,
        String message,
        Map<String, Object> payload,
        String traceId,
        Instant timestamp) {
}
