package com.petsitter.interfaces.advice;

import com.petsitter.application.exception.AbstractModuleExceptionHandler;
import com.petsitter.application.exception.ApiErrors;
import com.petsitter.application.exception.CommonErrorCode;
import com.petsitter.application.exception.ErrorResponse;
import com.petsitter.application.exception.FulfillErrorCode;
import com.petsitter.application.exception.FulfillException;
import com.petsitter.application.exception.IdempotencyException;
import com.petsitter.application.exception.MediaInspectionException;
import com.petsitter.application.exception.OrderErrorCode;
import com.petsitter.application.exception.OrderException;
import com.petsitter.application.exception.StorageUnavailableException;
import com.petsitter.interfaces.controller.fulfill.VisitController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 服務履約專屬例外對映（SD-FULFILL §6.1「例外」），只套 {@code interfaces.controller.fulfill}（F1–F19、FC1–FC2、FT1–FT2）。
 * 鎖逾時 / 死結 / {@code @Version} → 409 {@code ORDER_BUSY}、23505 → 409 {@code ORDER_BUSY}（含延遲約束 {@code ux_checklist_sort}）、
 * 其他完整性錯誤 → 500，沿用 {@link AbstractModuleExceptionHandler}。
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = VisitController.class)
public class FulfillExceptionHandler extends AbstractModuleExceptionHandler {

    @ExceptionHandler(FulfillException.class)
    public ResponseEntity<ErrorResponse> handleFulfill(FulfillException ex) {
        FulfillErrorCode code = ex.getCode();
        return ApiErrors.of(code.status(), code.name(), code.defaultMessage(), ex.getPayload());
    }

    /** SD-ORDER 介面（{@code lockForTransition} 404、排程 {@code orderId} 範圍 404 等）丟出的訂單例外，碼同 SD-ORDER。 */
    @ExceptionHandler(OrderException.class)
    public ResponseEntity<ErrorResponse> handleOrder(OrderException ex) {
        OrderErrorCode code = ex.getCode();
        return ApiErrors.of(code.status(), code.name(), code.defaultMessage(), ex.getPayload());
    }

    @ExceptionHandler(IdempotencyException.class)
    public ResponseEntity<ErrorResponse> handleIdempotency(IdempotencyException ex) {
        return ApiErrors.of(ex.getKind().code());
    }

    @ExceptionHandler({StorageUnavailableException.class, MediaInspectionException.class})
    public ResponseEntity<ErrorResponse> handleStorage(RuntimeException ex) {
        log.error("storage unavailable: {}", ex.getMessage());
        return ApiErrors.of(CommonErrorCode.STORAGE_UNAVAILABLE);
    }
}
