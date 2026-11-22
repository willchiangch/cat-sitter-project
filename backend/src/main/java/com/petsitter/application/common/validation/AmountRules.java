package com.petsitter.application.common.validation;

import com.petsitter.application.exception.RequestValidationException;

import java.math.BigDecimal;

/**
 * 金額 {@code BigDecimal} 範圍（SD-GLOBAL-SPEC §2.8「金額 BigDecimal 範圍」、NFR-003 SEC-INPUT-3、CQ-26）。
 * <p>
 * 在<b>任何運算之前</b>（{@code setScale}、{@code toBigIntegerExact}、{@code stripTrailingZeros}、比較、{@code toPlainString}）
 * 先檢查 {@code precision() ≤ 15} 且 {@code -15 ≤ scale() ≤ 15}；不符 → 400 {@code VALIDATION_FAILED}（{@code field} = 該欄）。
 * 通過後才做「必須為整數」與業務上下限檢查（由呼叫端負責）。兩個檢查都只讀欄位，不做運算（{@code 1e999999999} 也是常數時間）。
 */
public final class AmountRules {

    public static final int MAX_PRECISION = 15;
    public static final int MAX_ABS_SCALE = 15;

    private AmountRules() {
    }

    public static boolean withinRange(BigDecimal value) {
        return value.precision() <= MAX_PRECISION
                && value.scale() <= MAX_ABS_SCALE
                && value.scale() >= -MAX_ABS_SCALE;
    }

    /** 不符 → {@link RequestValidationException}（400 {@code VALIDATION_FAILED}，{@code field}）。null 視為通過（必填由呼叫端判斷）。 */
    public static BigDecimal require(BigDecimal value, String field) {
        if (value != null && !withinRange(value)) {
            throw new RequestValidationException(field);
        }
        return value;
    }
}
