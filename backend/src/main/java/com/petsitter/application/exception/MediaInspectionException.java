package com.petsitter.application.exception;

/** GCS 物件串流讀取 I/O 失敗（SD-FULFILL §6.4）；呼叫端包成 {@link StorageUnavailableException} → 503。 */
public class MediaInspectionException extends RuntimeException {

    public MediaInspectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
