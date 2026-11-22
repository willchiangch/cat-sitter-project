package com.petsitter.application.exception;

import org.springframework.http.HttpStatus;

/** 訂單模組錯誤碼（SD-ORDER §6.5，37 項中由 {@link OrderException} 攜帶者）。 */
public enum OrderErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "輸入資料格式不正確"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "無權限操作此訂單"),
    ROLE_MISMATCH(HttpStatus.FORBIDDEN, "目前身分無法執行此操作"),
    SUBSCRIPTION_LOCKED(HttpStatus.FORBIDDEN, "保母目前暫停接受新預約"),
    PLAN_REQUIRED(HttpStatus.FORBIDDEN, "目前方案不支援此功能，請升級方案"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "找不到資源"),
    ORDER_STATE_CONFLICT(HttpStatus.CONFLICT, "訂單狀態已變更，無法執行此操作"),
    PAYMENT_STATE_CONFLICT(HttpStatus.CONFLICT, "付款狀態已變更，無法執行此操作"),
    MODIFICATION_IN_PROGRESS(HttpStatus.CONFLICT, "此訂單有進行中的變更請求"),
    MODIFICATION_STATE_CONFLICT(HttpStatus.CONFLICT, "變更請求狀態已變更，無法執行此操作"),
    MODIFICATION_NOT_ALLOWED(HttpStatus.CONFLICT, "已無未開始的行程，無法發起變更"),
    VISITS_NOT_FINISHED(HttpStatus.CONFLICT, "行程尚未全部結束"),
    VISIT_ALREADY_STARTED(HttpStatus.CONFLICT, "要減少的行程已開始，請重新報價"),
    PAYMENT_ALREADY_SUBMITTED(HttpStatus.CONFLICT, "已上傳付款憑證，無法撤回或取消"),
    DEADLINE_PASSED(HttpStatus.CONFLICT, "已超過期限"),
    FIRST_VISIT_DATE_PASSED(HttpStatus.CONFLICT, "首個行程日期已過，無法報價"),
    PRICE_CHANGED(HttpStatus.CONFLICT, "金額已更新，請重新確認"),
    SELF_BOOKING_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT, "這是您自己的預約頁，無法預約"),
    MONTHLY_QUOTA_REACHED(HttpStatus.UNPROCESSABLE_CONTENT, "本月接單額度已滿"),
    INVALID_QUOTE_AMOUNT(HttpStatus.UNPROCESSABLE_CONTENT, "報價總額必須至少為 1"),
    INVALID_VISIT_DATE(HttpStatus.UNPROCESSABLE_CONTENT, "行程日期不符規則"),
    PAYOUT_ACCOUNT_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "請先設定收款帳號"),
    SITTER_NOT_BOOKABLE(HttpStatus.FORBIDDEN, "保母目前不開放預約"),
    PLAN_NOT_BOOKABLE(HttpStatus.UNPROCESSABLE_CONTENT, "此方案目前無法預約"),
    QUESTIONNAIRE_CHANGED(HttpStatus.CONFLICT, "保母的問卷已更新，請確認後再送出"),
    PROOF_FILE_TYPE_UNSUPPORTED(HttpStatus.UNPROCESSABLE_CONTENT, "僅支援 JPG、PNG、WebP 圖片"),
    PROOF_FILE_TOO_LARGE(HttpStatus.UNPROCESSABLE_CONTENT, "檔案大小不可超過 5 MB"),
    MODIFICATION_EMPTY(HttpStatus.UNPROCESSABLE_CONTENT, "變更內容不可為空"),
    PLAN_NOT_IN_ORDER(HttpStatus.UNPROCESSABLE_CONTENT, "新增行程只能選擇訂單內的方案"),
    INVALID_MODIFICATION_AMOUNT(HttpStatus.UNPROCESSABLE_CONTENT, "變更後總額不符規則"),
    INVALID_ADJUSTMENT_AMOUNT(HttpStatus.UNPROCESSABLE_CONTENT, "調解退款金額超過可退金額");

    private final HttpStatus status;
    private final String defaultMessage;

    OrderErrorCode(HttpStatus status, String defaultMessage) {
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
