package com.petsitter.application.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 訂單模組業務例外（SD-ORDER §6.4）：帶 {@link OrderErrorCode} 與 payload，由 {@code OrderExceptionHandler} 對映。 */
public class OrderException extends RuntimeException {

    private final OrderErrorCode code;
    private final Map<String, Object> payload;

    public OrderException(OrderErrorCode code) {
        this(code, Map.of());
    }

    public OrderException(OrderErrorCode code, Map<String, Object> payload) {
        super(code.name());
        this.code = code;
        this.payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    public static OrderException notFound() {
        return new OrderException(OrderErrorCode.NOT_FOUND);
    }

    public static OrderException forbidden() {
        return new OrderException(OrderErrorCode.FORBIDDEN);
    }

    public static OrderException roleMismatch() {
        return new OrderException(OrderErrorCode.ROLE_MISMATCH);
    }

    /** 400 {@code VALIDATION_FAILED}，payload 至少帶 {@code field}。 */
    public static OrderException validation(String field) {
        return new OrderException(OrderErrorCode.VALIDATION_FAILED, Map.of("field", field));
    }

    public static OrderException validation(String field, Map<String, Object> extra) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("field", field);
        p.putAll(extra);
        return new OrderException(OrderErrorCode.VALIDATION_FAILED, p);
    }

    public static OrderException stateConflict(Object currentStatus) {
        return new OrderException(OrderErrorCode.ORDER_STATE_CONFLICT, Map.of("currentStatus", String.valueOf(currentStatus)));
    }

    public static OrderException paymentConflict(Object currentStatus) {
        return new OrderException(OrderErrorCode.PAYMENT_STATE_CONFLICT, Map.of("currentStatus", String.valueOf(currentStatus)));
    }

    public static OrderException modificationConflict(Object currentStatus) {
        return new OrderException(OrderErrorCode.MODIFICATION_STATE_CONFLICT, Map.of("currentStatus", String.valueOf(currentStatus)));
    }

    public static OrderException deadline(String deadline, Object deadlineAt) {
        return new OrderException(OrderErrorCode.DEADLINE_PASSED, Map.of("deadline", deadline, "deadlineAt", String.valueOf(deadlineAt)));
    }

    public OrderErrorCode getCode() {
        return code;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }
}
