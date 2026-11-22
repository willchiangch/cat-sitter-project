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
    private static final java.util.regex.Pattern TRACE_ID_PATTERN = java.util.regex.Pattern.compile("^[0-9a-f]{32}$");

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
        return build(request, code, message, null);
    }

    public static ErrorResponse build(HttpServletRequest request, String code, String message, Map<String, Object> payload) {
        Map<String, Object> safePayload = (payload == null || payload.isEmpty()) ? null : payload;
        ErrorResponse body = new ErrorResponse(code, message, safePayload, traceId(request), Instant.now());
        request.setAttribute(ERROR_RESPONSE_ATTRIBUTE, body);
        return body;
    }

    /**
     * Cloud Run 帶入的 trace id（{@code X-Cloud-Trace-Context: TRACE_ID/SPAN;o=1}）。SD-GLOBAL-SPEC §2.6、NFR-003 SEC-LOG-5：
     * 斜線前的部分必須完全符合 {@code ^[0-9a-f]{32}$} 才採用；缺漏、格式不符、含 CR / LF 或其他字元一律重新產生
     * （32 位小寫十六進位）。未驗證的標頭值不得進回應或 log。
     */
    static String traceId(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object existing = request.getAttribute(TRACE_ID_ATTRIBUTE);
        if (existing instanceof String s) {
            return s;
        }
        String header;
        try {
            header = request.getHeader(CLOUD_TRACE_HEADER);
        } catch (RuntimeException e) {
            // StrictHttpFirewall 對含 CR / LF 等字元的標頭值於讀取時拋 RequestRejectedException：視同格式不符，重新產生
            header = null;
        }
        String traceId = parseTraceHeader(header);
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        return traceId;
    }

    /** 給 log 使用（同一請求與錯誤回應的 traceId 相同）。 */
    public static String traceIdOf(HttpServletRequest request) {
        return traceId(request);
    }

    static String parseTraceHeader(String header) {
        if (header == null) {
            return null;
        }
        int slash = header.indexOf('/');
        String candidate = slash >= 0 ? header.substring(0, slash) : header;
        return TRACE_ID_PATTERN.matcher(candidate).matches() ? candidate : null;
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            return servlet.getRequest();
        }
        return null;
    }
}
