package com.petsitter.fulfill;

import com.petsitter.application.exception.ErrorResponse;
import com.petsitter.interfaces.advice.FulfillExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** SD-FULFILL §6.1「例外」/ SD-GLOBAL-SPEC §2.6：只有 23505 → 409 ORDER_BUSY；其他完整性違反 → 500（單元測試）。 */
@DisplayName("SD-FULFILL FulfillExceptionMappingTest")
class FulfillExceptionMappingTest {

    private final FulfillExceptionHandler handler = new FulfillExceptionHandler();

    private static DataIntegrityViolationException violation(String sqlState) {
        return new DataIntegrityViolationException("x", new SQLException("violation", sqlState));
    }

    @Test
    @DisplayName("§6.1 23505（含延遲約束 ux_checklist_sort）→ 409 ORDER_BUSY + Retry-After: 1；@Version 衝突 → 409 ORDER_BUSY")
    void uniqueViolationIsBusy() {
        ResponseEntity<ErrorResponse> r = handler.handlePersistence(violation("23505"));
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(r.getBody().code()).isEqualTo("ORDER_BUSY");
        assertThat(r.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        ResponseEntity<ErrorResponse> v = handler.handlePersistence(new ObjectOptimisticLockingFailureException("Visit", "id"));
        assertThat(v.getBody().code()).isEqualTo("ORDER_BUSY");
    }

    @ParameterizedTest
    @ValueSource(strings = {"23514", "23502", "23503"})
    @DisplayName("§6.1 23514 / 23502 / 23503 → 500 INTERNAL_ERROR（不偽裝成可重試）")
    void otherViolationsAreInternal(String sqlState) {
        ResponseEntity<ErrorResponse> r = handler.handlePersistence(violation(sqlState));
        assertThat(r.getStatusCode().value()).isEqualTo(500);
        assertThat(r.getBody().code()).isEqualTo("INTERNAL_ERROR");
    }
}
