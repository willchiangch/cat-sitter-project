package com.petsitter.application.service.fulfill;

import java.time.LocalDate;

/** {@code VisitQueryService.effectiveServiceDateRanges} 的結果：單一訂單有效行程（status ≠ CANCELLED）的首末日。 */
public record ServiceDateRange(LocalDate first, LocalDate last) {
}
