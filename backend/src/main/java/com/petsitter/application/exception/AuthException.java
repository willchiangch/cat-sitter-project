package com.petsitter.application.exception;

import org.springframework.http.HttpStatus;

/**
 * SD-000 通用認證例外（目前用於 refreshToken() 找不到/已過期的情況）。
 * 過去這類情況直接丟裸 RuntimeException，沒有對應的 @ExceptionHandler 接住，
 * 最終變成沒有 message 欄位的裸 500，前端只能顯示 axios 自己兜的
 * 「Request failed with status code 500」，看不出真正原因。
 */
public class AuthException extends RuntimeException {
    private final HttpStatus status;
    private final String error;

    public AuthException(HttpStatus status, String error, String message) {
        super(message);
        this.status = status;
        this.error = error;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }
}
