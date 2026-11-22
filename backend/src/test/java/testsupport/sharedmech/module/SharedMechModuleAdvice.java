package testsupport.sharedmech.module;

import com.petsitter.application.exception.AbstractModuleExceptionHandler;
import com.petsitter.application.exception.ApiErrors;
import com.petsitter.application.exception.ErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 模擬重建模組的專屬 advice（如 B 階段 OrderExceptionHandler）：繼承共用基底 + 自己的業務碼。 */
@RestControllerAdvice(basePackageClasses = SharedMechModuleController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SharedMechModuleAdvice extends AbstractModuleExceptionHandler {

    public static final String NAMED_CONSTRAINT = "ux_shared_mech_named";

    @Override
    protected Map<String, ConstraintError> uniqueViolationMappings() {
        return Map.of(NAMED_CONSTRAINT,
                new ConstraintError(HttpStatus.CONFLICT, "TEST_NAMED_CONFLICT", "具名約束衝突"));
    }

    @ExceptionHandler(TestBusinessConflictException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(TestBusinessConflictException ex) {
        return ApiErrors.of(HttpStatus.CONFLICT, "TEST_STATE_CONFLICT", "狀態不符",
                Map.of("currentStatus", "CANCELLED"));
    }

    public static class TestBusinessConflictException extends RuntimeException {
    }
}
