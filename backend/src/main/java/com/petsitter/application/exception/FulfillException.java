package com.petsitter.application.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 服務履約模組業務例外（SD-FULFILL §6.4）：帶 {@link FulfillErrorCode} 與 payload，由 {@code FulfillExceptionHandler} 對映。 */
public class FulfillException extends RuntimeException {

    private final FulfillErrorCode code;
    private final Map<String, Object> payload;

    public FulfillException(FulfillErrorCode code) {
        this(code, Map.of());
    }

    public FulfillException(FulfillErrorCode code, Map<String, Object> payload) {
        super(code.name());
        this.code = code;
        Map<String, Object> copy = new LinkedHashMap<>();
        if (payload != null) {
            payload.forEach((k, v) -> {
                if (v != null) {
                    copy.put(k, v);
                }
            });
        }
        this.payload = Collections.unmodifiableMap(copy);
    }

    public static FulfillException notFound() {
        return new FulfillException(FulfillErrorCode.NOT_FOUND);
    }

    public static FulfillException forbidden() {
        return new FulfillException(FulfillErrorCode.FORBIDDEN);
    }

    public static FulfillException roleMismatch() {
        return new FulfillException(FulfillErrorCode.ROLE_MISMATCH);
    }

    public static FulfillException validation(String field) {
        return new FulfillException(FulfillErrorCode.VALIDATION_FAILED, Map.of("field", field));
    }

    public static FulfillException validation(String field, Map<String, Object> extra) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("field", field);
        p.putAll(extra);
        return new FulfillException(FulfillErrorCode.VALIDATION_FAILED, p);
    }

    public static FulfillException orderState(Object currentStatus) {
        return new FulfillException(FulfillErrorCode.ORDER_STATE_CONFLICT, Map.of("currentStatus", String.valueOf(currentStatus)));
    }

    /** {@code VISIT_STATE_CONFLICT}：{@code currentVisitStatus}；DONE 時帶 {@code completionSource}（PRD-008 CQ-11）。 */
    public static FulfillException visitState(Object currentVisitStatus, String completionSource) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("currentVisitStatus", String.valueOf(currentVisitStatus));
        if ("DONE".equals(String.valueOf(currentVisitStatus)) && completionSource != null) {
            p.put("completionSource", completionSource);
        }
        return new FulfillException(FulfillErrorCode.VISIT_STATE_CONFLICT, p);
    }

    public static FulfillException careLogState(String currentCareLogStatus) {
        return new FulfillException(FulfillErrorCode.CARE_LOG_STATE_CONFLICT, Map.of("currentCareLogStatus", currentCareLogStatus));
    }

    public static FulfillException deadline(String deadline, java.time.OffsetDateTime deadlineAt) {
        return new FulfillException(FulfillErrorCode.DEADLINE_PASSED, Map.of("deadline", deadline, "deadlineAt", ts(deadlineAt)));
    }

    /** payload 時間一律 UTC ISO-8601（秒位固定輸出），與回應 body 的時間格式一致。 */
    public static String ts(java.time.OffsetDateTime t) {
        return t == null ? null : t.withOffsetSameInstant(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    public FulfillErrorCode getCode() {
        return code;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }
}
