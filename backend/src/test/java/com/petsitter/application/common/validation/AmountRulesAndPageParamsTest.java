package com.petsitter.application.common.validation;

import com.petsitter.application.exception.RequestValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SD-GLOBAL-SPEC §2.8 金額 BigDecimal 範圍與分頁（NFR-003 SEC-INPUT-3、SEC-INPUT-5）。 */
@DisplayName("NFR-003 SEC-INPUT-3 / 5 AmountRules、PageParams")
class AmountRulesAndPageParamsTest {

    @Test
    @DisplayName("AmountRules：precision ≤ 15 且 -15 ≤ scale ≤ 15，否則 400 field")
    void amountRules() {
        assertThat(AmountRules.withinRange(new BigDecimal("999999"))).isTrue();
        assertThat(AmountRules.withinRange(new BigDecimal("999999999999999"))).isTrue();
        assertThat(AmountRules.withinRange(new BigDecimal("1000000000000000"))).isFalse();
        assertThat(AmountRules.withinRange(new BigDecimal("1e15"))).isTrue();
        assertThat(AmountRules.withinRange(new BigDecimal("1e16"))).isFalse();
        assertThat(AmountRules.withinRange(new BigDecimal("1e999999999"))).isFalse();
        assertThat(AmountRules.withinRange(new BigDecimal("1e-999999999"))).isFalse();
        assertThat(AmountRules.withinRange(new BigDecimal("0.000000000000001"))).isTrue();
        assertThat(AmountRules.withinRange(new BigDecimal("0.0000000000000001"))).isFalse();
        assertThat(AmountRules.require(null, "x")).isNull();
        assertThatThrownBy(() -> AmountRules.require(new BigDecimal("1e999999999"), "price"))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(((RequestValidationException) e).getPayload()).containsEntry("field", "price"));
    }

    @Test
    @DisplayName("PageParams：size 預設 20、全域上限 100、模組可更小；page < 0 → 400")
    void pageParams() {
        assertThat(PageParams.of(null, null)).isEqualTo(new PageParams(0, 20));
        assertThat(PageParams.of(2, 100)).isEqualTo(new PageParams(2, 100));
        assertThatThrownBy(() -> PageParams.of(0, 101)).isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(((RequestValidationException) e).getPayload()).containsEntry("field", "size").containsEntry("max", 100));
        assertThatThrownBy(() -> PageParams.of(0, 0)).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> PageParams.of(-1, 10)).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> PageParams.of(0, 51, 50)).isInstanceOf(RequestValidationException.class);
        assertThat(PageParams.of(0, null, 500).size()).isEqualTo(20); // 模組上限大於全域時以 100 為準
        assertThatThrownBy(() -> PageParams.of(0, 101, 500)).isInstanceOf(RequestValidationException.class);
    }
}
