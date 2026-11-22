package com.petsitter.application.exception;

import org.springframework.http.HttpStatus;

/** 服務履約模組錯誤碼（SD-FULFILL §6.5，由 {@link FulfillException} 攜帶）。 */
public enum FulfillErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "輸入資料格式不正確"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "無權限操作此行程"),
    ROLE_MISMATCH(HttpStatus.FORBIDDEN, "目前身分無法執行此操作"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "找不到資源"),
    ORDER_STATE_CONFLICT(HttpStatus.CONFLICT, "訂單狀態已變更，無法執行此操作"),
    VISIT_STATE_CONFLICT(HttpStatus.CONFLICT, "行程狀態已變更，無法執行此操作"),
    CARE_LOG_STATE_CONFLICT(HttpStatus.CONFLICT, "照護日誌已送出，無法修改"),
    CARE_LOG_UPLOAD_EXPIRED(HttpStatus.CONFLICT, "上傳已逾時，請重新選擇檔案上傳"),
    DEADLINE_PASSED(HttpStatus.CONFLICT, "已超過期限"),
    VISIT_DATE_NOT_REACHED(HttpStatus.UNPROCESSABLE_CONTENT, "尚未到行程日期，無法開始"),
    CARE_LOG_CONTENT_EMPTY(HttpStatus.UNPROCESSABLE_CONTENT, "請填寫照護日誌內容"),
    CARE_LOG_MEDIA_TYPE_UNSUPPORTED(HttpStatus.UNPROCESSABLE_CONTENT, "照片僅支援 JPG、PNG、WebP；影片僅支援 MP4、MOV"),
    CARE_LOG_MEDIA_TOO_LARGE(HttpStatus.UNPROCESSABLE_CONTENT, "檔案過大"),
    CARE_LOG_VIDEO_DURATION_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "影片長度需為 15–30 秒"),
    CARE_LOG_MEDIA_QUOTA_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, "此行程媒體額度已用完");

    private final HttpStatus status;
    private final String defaultMessage;

    FulfillErrorCode(HttpStatus status, String defaultMessage) {
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
