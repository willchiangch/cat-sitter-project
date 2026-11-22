package com.petsitter.application.exception;

import jakarta.persistence.PersistenceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

/**
 * 重建模組專屬 advice 的共用基底（SD-GLOBAL-SPEC §2.6「鎖與資料完整性」、SD-ORDER §6.4、SD-FULFILL §6.1）。
 * <p>
 * 子類別標 {@code @RestControllerAdvice(basePackageClasses = 模組 controller 套件)} 與
 * {@code @Order(Ordered.HIGHEST_PRECEDENCE)}，只套該模組 controller；未重建模組仍走 {@link GlobalExceptionHandler}
 * 的現行對映（SD-ORDER §6.4「其他模組維持現行對映」）。
 * <ul>
 *   <li>{@link OrderBusyException}、SQLSTATE 55P03 / 40P01、{@code PessimisticLockingFailureException} 家族、
 *       {@code @Version} 衝突 → 409 {@code ORDER_BUSY} + {@code Retry-After: 1}</li>
 *   <li>SQLSTATE 23505 → {@link #uniqueViolationMappings()} 依約束名對應模組碼；未列名者 409 {@code ORDER_BUSY}</li>
 *   <li>其他資料存取錯誤（23514 / 23502 / 23503 …）→ 500 {@code INTERNAL_ERROR} 並記 ERROR log</li>
 * </ul>
 * 刻意不放 {@code Exception} 全捕捉：advice 依序比對，模組 advice 只要有任何方法可處理就不會落到全域 advice，
 * 全捕捉會吃掉 {@code AuthorizationDeniedException} 等全域對映。
 */
@Slf4j
public abstract class AbstractModuleExceptionHandler {

    /** 約束名 → 模組錯誤（只對 SQLSTATE 23505 生效）。 */
    public record ConstraintError(HttpStatus status, String code, String message) {
    }

    /** 子類別覆寫：例 SD-ORDER {@code ux_modreq_open_per_order} → 409 {@code MODIFICATION_IN_PROGRESS}。 */
    protected Map<String, ConstraintError> uniqueViolationMappings() {
        return Map.of();
    }

    @ExceptionHandler(OrderBusyException.class)
    public ResponseEntity<ErrorResponse> handleOrderBusy(OrderBusyException ex) {
        return ApiErrors.orderBusy();
    }

    @ExceptionHandler({DataAccessException.class, PersistenceException.class, TransactionSystemException.class})
    public ResponseEntity<ErrorResponse> handlePersistence(Exception ex) {
        PersistenceErrorClassifier.Classification c = PersistenceErrorClassifier.classify(ex);
        switch (c.kind()) {
            case LOCK_UNAVAILABLE, VERSION_CONFLICT -> {
                log.info("ORDER_BUSY: {}", PersistenceErrorClassifier.describe(c));
                return ApiErrors.orderBusy();
            }
            case UNIQUE_VIOLATION -> {
                ConstraintError mapped = c.constraintName() == null ? null
                        : uniqueViolationMappings().get(c.constraintName());
                if (mapped != null) {
                    return ApiErrors.of(mapped.status(), mapped.code(), mapped.message());
                }
                log.warn("unique violation mapped to ORDER_BUSY: {}", PersistenceErrorClassifier.describe(c));
                return ApiErrors.orderBusy();
            }
            default -> {
                log.error("data access error mapped to INTERNAL_ERROR: {}", PersistenceErrorClassifier.describe(c),
                        PersistenceErrorClassifier.sanitized(ex));
                return ApiErrors.of(CommonErrorCode.INTERNAL_ERROR);
            }
        }
    }
}
