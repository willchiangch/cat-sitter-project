package com.petsitter.interfaces.advice;

import com.petsitter.application.exception.AbstractModuleExceptionHandler;
import com.petsitter.application.exception.ApiErrors;
import com.petsitter.application.exception.CommonErrorCode;
import com.petsitter.application.exception.ErrorResponse;
import com.petsitter.application.exception.IdempotencyException;
import com.petsitter.application.exception.OrderErrorCode;
import com.petsitter.application.exception.OrderException;
import com.petsitter.application.exception.StorageUnavailableException;
import com.petsitter.application.exception.VisitNotPendingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/**
 * 訂單專屬例外對映（SD-ORDER §6.4），只套 {@code interfaces.controller.order}（E1–E31、C1–C10）。
 * 鎖逾時 / 死結 / {@code @Version} / 完整性錯誤沿用 {@link AbstractModuleExceptionHandler}。
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.petsitter.interfaces.controller.order")
public class OrderExceptionHandler extends AbstractModuleExceptionHandler {

    private static final Map<String, ConstraintError> UNIQUE = Map.of(
            "ux_modreq_open_per_order", new ConstraintError(HttpStatus.CONFLICT,
                    OrderErrorCode.MODIFICATION_IN_PROGRESS.name(), OrderErrorCode.MODIFICATION_IN_PROGRESS.defaultMessage()),
            "ux_admin_tasks_open", new ConstraintError(HttpStatus.CONFLICT,
                    OrderErrorCode.ORDER_STATE_CONFLICT.name(), OrderErrorCode.ORDER_STATE_CONFLICT.defaultMessage()));

    @Override
    protected Map<String, ConstraintError> uniqueViolationMappings() {
        return UNIQUE;
    }

    @ExceptionHandler(OrderException.class)
    public ResponseEntity<ErrorResponse> handleOrder(OrderException ex) {
        OrderErrorCode code = ex.getCode();
        return ApiErrors.of(code.status(), code.name(), code.defaultMessage(), ex.getPayload());
    }

    @ExceptionHandler(VisitNotPendingException.class)
    public ResponseEntity<ErrorResponse> handleVisitNotPending(VisitNotPendingException ex) {
        OrderErrorCode code = OrderErrorCode.VISIT_ALREADY_STARTED;
        return ApiErrors.of(code.status(), code.name(), code.defaultMessage(),
                Map.of("visitIds", List.copyOf(ex.getVisitIds()).stream().map(String::valueOf).sorted().toList()));
    }

    @ExceptionHandler(IdempotencyException.class)
    public ResponseEntity<ErrorResponse> handleIdempotency(IdempotencyException ex) {
        return ApiErrors.of(ex.getKind().code());
    }

    @ExceptionHandler(StorageUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleStorage(StorageUnavailableException ex) {
        log.error("storage unavailable: {}", ex.getMessage());
        return ApiErrors.of(CommonErrorCode.STORAGE_UNAVAILABLE);
    }
}
