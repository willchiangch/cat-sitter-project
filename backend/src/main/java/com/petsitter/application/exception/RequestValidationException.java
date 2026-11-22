package com.petsitter.application.exception;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 共用層（冪等攔截器、共用驗證工具）拋出的 400 {@code VALIDATION_FAILED}，payload 至少帶 {@code field}
 * （SD-GLOBAL-SPEC §2.4 / §2.6）。模組自己的第 6 步驗證仍用各模組例外（例：{@code OrderException(VALIDATION_FAILED)}）。
 */
public class RequestValidationException extends RuntimeException {

    private final Map<String, Object> payload;

    public RequestValidationException(String field) {
        this(field, Map.of());
    }

    public RequestValidationException(String field, Map<String, Object> extra) {
        super(CommonErrorCode.VALIDATION_FAILED.defaultMessage());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("field", field);
        p.putAll(extra);
        this.payload = Map.copyOf(p);
    }

    public Map<String, Object> getPayload() {
        return payload;
    }
}
