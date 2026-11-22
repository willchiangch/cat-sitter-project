package com.petsitter.application.exception;

import org.springframework.http.HttpStatus;

/**
 * 跨模組共用錯誤碼（baseline §3.7；SD-ORDER §6.5 #1–4、#7、#19–21、#32、#37）。
 * 模組專屬碼由各模組的 ErrorCode 列舉定義。
 */
public enum CommonErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "輸入資料格式不正確"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "請先登入系統"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "無權限執行此操作"),
    ROLE_MISMATCH(HttpStatus.FORBIDDEN, "目前身分無法執行此操作"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "找不到資源"),
    ORDER_BUSY(HttpStatus.CONFLICT, "資料正在處理中，請稍後再試"),
    IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT, "相同請求正在處理中"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, "此請求識別碼已用於不同內容"),
    STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "檔案儲存服務暫時無法使用，請稍後再試"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "系統發生錯誤，請稍後再試");

    private final HttpStatus status;
    private final String defaultMessage;

    CommonErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
