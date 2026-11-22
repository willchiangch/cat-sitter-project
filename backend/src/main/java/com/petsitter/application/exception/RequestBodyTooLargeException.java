package com.petsitter.application.exception;

import java.io.IOException;

/**
 * 非 multipart 請求 body 超過 {@code RequestBodySizeLimitFilter} 上限（SD-GLOBAL-SPEC §2.8、NFR-003 SEC-INPUT-1）。
 * 讀串流途中拋出（繼承 {@link IOException}，符合 {@code InputStream.read} 的語意）；{@code GlobalExceptionHandler}
 * 對映 400 {@code VALIDATION_FAILED}（{@code field=body}、{@code maxBytes}），也會沿 cause chain 從
 * {@code HttpMessageNotReadableException} 中辨識。
 */
public class RequestBodyTooLargeException extends IOException {

    private final long maxBytes;

    public RequestBodyTooLargeException(long maxBytes) {
        super("request body exceeds limit");
        this.maxBytes = maxBytes;
    }

    public long getMaxBytes() {
        return maxBytes;
    }
}
