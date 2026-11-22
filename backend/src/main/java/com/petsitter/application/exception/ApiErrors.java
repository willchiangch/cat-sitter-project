package com.petsitter.application.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 產生 §2.6 格式錯誤回應的唯一入口（全域 advice、模組 advice、security entry point 共用）。
 * <p>
 * 產生的 {@link ErrorResponse} 會同時放進 request attribute {@link #ERROR_RESPONSE_ATTRIBUTE}，
 * 讓冪等機制在請求結束時能依錯誤碼決定「記錄回放 / 刪除紀錄」（SD-GLOBAL-SPEC §2.4 失敗段）。
 */
public final class ApiErrors {

    public static final String ERROR_RESPONSE_ATTRIBUTE = ApiErrors.class.getName() + ".errorResponse";
    static final String TRACE_ID_ATTRIBUTE = ApiErrors.class.getName() + ".traceId";
    private static final String CLOUD_TRACE_HEADER = "X-Cloud-Trace-Context";

    private ApiErrors() {
    }

    public static ResponseEntity<ErrorResponse> of(HttpStatusCode status, String code, String message) {
        return of(status, code, message, null);
    }

    public static ResponseEntity<ErrorResponse> of(HttpStatusCode status, String code, String message,
                                                   Map<String, Object> payload) {
        ErrorResponse body = build(code, message, payload);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (CommonErrorCode.ORDER_BUSY.name().equals(code)) {
            builder.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return builder.body(body);
    }

    public static ResponseEntity<ErrorResponse> of(CommonErrorCode code) {
        return of(code.status(), code.name(), code.defaultMessage(), null);
    }

    public static ResponseEntity<ErrorResponse> of(CommonErrorCode code, Map<String, Object> payload) {
        return of(code.status(), code.name(), code.defaultMessage(), payload);
    }

    public static ResponseEntity<ErrorResponse> orderBusy() {
        return of(CommonErrorCode.ORDER_BUSY);
    }

    /** 不經 ResponseEntity 時（security entry point）使用；同樣會寫入 request attribute。 */
    public static ErrorResponse build(String code, String message, Map<String, Object> payload) {
        Map<String, Object> safePayload = (payload == null || payload.isEmpty()) ? null : payload;
        HttpServletRequest request = currentRequest();
        ErrorResponse body = new ErrorResponse(code, message, safePayload, traceId(request), Instant.now());
        if (request != null) {
            request.setAttribute(ERROR_RESPONSE_ATTRIBUTE, body);
        }
        return body;
    }

    public static ErrorResponse build(HttpServletRequest request, String code, String message) {
        ErrorResponse body = new ErrorResponse(code, message, null, traceId(request), Instant.now());
        request.setAttribute(ERROR_RESPONSE_ATTRIBUTE, body);
        return body;
    }

    /** Cloud Run 帶入的 trace id（{@code X-Cloud-Trace-Context: TRACE_ID/SPAN;o=1}）；沒有則每個請求產生一個。 */
    static String traceId(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object existing = request.getAttribute(TRACE_ID_ATTRIBUTE);
        if (existing instanceof String s) {
            return s;
        }
        String header = request.getHeader(CLOUD_TRACE_HEADER);
        String traceId;
        if (header != null && !header.isBlank()) {
            int slash = header.indexOf('/');
            traceId = (slash > 0 ? header.substring(0, slash) : header).strip();
        } else {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        return traceId;
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            return servlet.getRequest();
        }
        return null;
    }
}
