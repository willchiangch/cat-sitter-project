package com.petsitter.application.service.order;

import com.petsitter.application.common.idempotency.IdempotencyContext;
import com.petsitter.application.common.idempotency.IdempotencyService;
import com.petsitter.application.common.text.TextRules;
import com.petsitter.application.common.validation.AmountRules;
import com.petsitter.application.exception.OrderException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

/**
 * 判斷順序第 6 步（G6）的欄位驗證（SD-ORDER §6.1「欄位驗證」）：DTO 不掛 {@code @Valid}，由此拋
 * {@code OrderException(VALIDATION_FAILED)}（payload {@code field}）。文字一律 {@link TextRules}（GLOBAL §2.7）。
 */
@Component
@RequiredArgsConstructor
public class OrderRequestValidator {

    public static final String IDEMPOTENCY_FIELD = "Idempotency-Key";

    private final IdempotencyService idempotencyService;
    private final OrderSettings settings;

    /** 冪等端點缺 / 錯 key → 400 {@code field=Idempotency-Key}。 */
    public IdempotencyContext requireIdempotencyKey() {
        return idempotencyService.currentContext().orElseThrow(() -> OrderException.validation(IDEMPOTENCY_FIELD));
    }

    /**
     * 整數金額：先檢查 {@link AmountRules}（GLOBAL §2.8，任何運算之前），再要求整數字面值（{@code 100.5}、{@code 100.0} 都 400），
     * 範圍 [min, max]。
     */
    public int amount(BigDecimal value, String field, int min, int max, boolean required, int defaultValue) {
        if (value == null) {
            if (required) {
                throw OrderException.validation(field);
            }
            return defaultValue;
        }
        if (!AmountRules.withinRange(value)) {
            throw OrderException.validation(field);
        }
        if (value.scale() > 0 || value.stripTrailingZeros().scale() > 0) {
            throw OrderException.validation(field);
        }
        BigDecimal minBd = BigDecimal.valueOf(min);
        BigDecimal maxBd = BigDecimal.valueOf(max);
        if (value.compareTo(minBd) < 0 || value.compareTo(maxBd) > 0) {
            throw OrderException.validation(field, Map.of("max", max));
        }
        return value.intValueExact();
    }

    public int amount(BigDecimal value, String field) {
        return amount(value, field, 0, settings.getMaxAmount(), true, 0);
    }

    /** 必填文字：strip 後 1–max code point，回傳 strip 後的值。 */
    public String requiredText(String value, String field, int max) {
        String s = TextRules.strip(value);
        if (s == null || s.isEmpty() || TextRules.codePointLength(s) > max) {
            throw OrderException.validation(field);
        }
        return s;
    }

    /** 選填文字：strip 後為空 → null；超過 max → 400。 */
    public String optionalText(String value, String field, int max) {
        String s = TextRules.stripToNull(value);
        if (s != null && TextRules.codePointLength(s) > max) {
            throw OrderException.validation(field);
        }
        return s;
    }

    public UUID uuid(String value, String field) {
        if (value == null) {
            throw OrderException.validation(field);
        }
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) {
                throw OrderException.validation(field);
            }
            return id;
        } catch (IllegalArgumentException e) {
            throw OrderException.validation(field);
        }
    }

    public LocalDate date(String value, String field) {
        if (value == null) {
            throw OrderException.validation(field);
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw OrderException.validation(field);
        }
    }
}
