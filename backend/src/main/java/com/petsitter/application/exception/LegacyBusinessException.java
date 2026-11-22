package com.petsitter.application.exception;

import org.springframework.http.HttpStatus;

/**
 * 未重建模組（帳號、毛孩、門禁）原本以 {@code IllegalArgumentException} / {@code IllegalStateException} 表達的業務錯誤。
 * <p>
 * SD-GLOBAL-SPEC §2.6（NFR-003 SEC-LOG-2、G-14）：框架 / JDK 例外的 {@code getMessage()} 不得出現在回應，
 * {@code GlobalExceptionHandler} 對 {@code IllegalArgumentException} / {@code IllegalStateException} 改回固定文案。
 * 為保留這些模組既有的業務文案與錯誤碼（{@code INVALID_PARAMETER} 400、{@code STATE_CONFLICT} 409），
 * 原呼叫點改拋本例外（本專案例外，建構時傳入的業務文案可用）。各模組重建時改用模組自己的例外。
 */
public class LegacyBusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String error;

    public LegacyBusinessException(HttpStatus status, String error, String message) {
        super(message);
        this.status = status;
        this.error = error;
    }

    /** 原 {@code IllegalArgumentException} → 400 {@code INVALID_PARAMETER}。 */
    public static LegacyBusinessException invalidParameter(String message) {
        return new LegacyBusinessException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", message);
    }

    /** 原 {@code IllegalStateException} → 409 {@code STATE_CONFLICT}。 */
    public static LegacyBusinessException stateConflict(String message) {
        return new LegacyBusinessException(HttpStatus.CONFLICT, "STATE_CONFLICT", message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }
}
