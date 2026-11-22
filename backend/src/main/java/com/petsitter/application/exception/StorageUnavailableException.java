package com.petsitter.application.exception;

/** 物件儲存（GCS）呼叫失敗 → 503 {@code STORAGE_UNAVAILABLE}（baseline 跨模組碼；SD-ORDER §6.4）。冪等紀錄刪除。 */
public class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
