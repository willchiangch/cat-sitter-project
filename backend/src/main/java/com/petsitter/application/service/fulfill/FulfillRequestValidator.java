package com.petsitter.application.service.fulfill;

import com.petsitter.application.common.idempotency.IdempotencyContext;
import com.petsitter.application.common.idempotency.IdempotencyService;
import com.petsitter.application.common.text.TextRules;
import com.petsitter.application.exception.FulfillException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * 判斷順序第 6 步（G6）欄位驗證（SD-FULFILL §6.1 DTO 型別）：DTO 以 {@link JsonNode} 接收型別敏感欄位，在此判型別
 * （避免 Jackson 把數字轉字串、把字串 "true" 轉布林）；字數一律 code point、空白依 {@link TextRules}。
 */
@Component
@RequiredArgsConstructor
public class FulfillRequestValidator {

    public static final String IDEMPOTENCY_FIELD = "Idempotency-Key";

    private final IdempotencyService idempotencyService;

    public IdempotencyContext requireIdempotencyKey() {
        return idempotencyService.currentContext().orElseThrow(() -> FulfillException.validation(IDEMPOTENCY_FIELD));
    }

    public static boolean present(JsonNode node) {
        return node != null && !node.isNull() && !node.isMissingNode();
    }

    /** 字串欄（原文，不 strip）；非字串 / 缺 → 400。 */
    public String rawString(JsonNode node, String field) {
        if (!present(node) || !node.isString()) {
            throw FulfillException.validation(field);
        }
        return node.stringValue();
    }

    /** 名稱類：strip 後 {@code min}–{@code max} code point，回傳 strip 後的值。 */
    public String strippedString(JsonNode node, String field, int min, int max) {
        String s = TextRules.strip(rawString(node, field));
        int len = TextRules.codePointLength(s);
        if (len < min || len > max) {
            throw FulfillException.validation(field);
        }
        return s;
    }

    /** 日誌文字：原文 ≤ max code point（可為空字串）。 */
    public String text(JsonNode node, String field, int max) {
        String s = rawString(node, field);
        if (TextRules.codePointLength(s) > max) {
            throw FulfillException.validation(field, java.util.Map.of("max", max));
        }
        return s;
    }

    public boolean bool(JsonNode node, String field) {
        if (!present(node) || !node.isBoolean()) {
            throw FulfillException.validation(field);
        }
        return node.booleanValue();
    }

    public UUID uuid(JsonNode node, String field) {
        String s = rawString(node, field);
        try {
            UUID id = UUID.fromString(s);
            if (!id.toString().equalsIgnoreCase(s)) {
                throw FulfillException.validation(field);
            }
            return id;
        } catch (IllegalArgumentException e) {
            throw FulfillException.validation(field);
        }
    }
}
