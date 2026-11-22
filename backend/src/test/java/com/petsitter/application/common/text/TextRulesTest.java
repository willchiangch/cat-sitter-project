package com.petsitter.application.common.text;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SD-GLOBAL §2.7 文字欄位的空白與字數")
class TextRulesTest {

    @Test
    @DisplayName("strip 等同 String.strip()：去 U+3000 全形空白、Tab、換行；trim() 去不掉 U+3000")
    void stripUnicodeWhitespace() {
        String raw = "　\t 你好 \n　";
        assertThat(TextRules.strip(raw)).isEqualTo("你好").isEqualTo(raw.strip());
        assertThat(raw.trim()).isNotEqualTo("你好");
        assertThat(TextRules.strip("a　b")).isEqualTo("a　b");
        assertThat(TextRules.strip(null)).isNull();
    }

    @Test
    @DisplayName("U+00A0 不換行空白也視為空白（GLOBAL §2.7 修訂）：頭尾去除、中間保留、全為 U+00A0 → 空")
    void stripNoBreakSpace() {
        assertThat(TextRules.isBlankChar(0x00A0)).isTrue();
        assertThat(TextRules.strip(" 　原因  ")).isEqualTo("原因");
        assertThat(TextRules.strip("a b")).isEqualTo("a b");
        assertThat(TextRules.stripToNull("  ")).isNull();
        assertThat(TextRules.strippedLength(" 好 ")).isEqualTo(1);
        // JDK String.strip() 不去 U+00A0，本實作刻意比它多去一個字元
        assertThat(" x".strip()).isEqualTo(" x");
    }

    @Test
    @DisplayName("選填欄位 strip 後為空 → NULL；isBlank 判定")
    void stripToNull() {
        assertThat(TextRules.stripToNull("　 \t")).isNull();
        assertThat(TextRules.stripToNull(" a ")).isEqualTo("a");
        assertThat(TextRules.isBlank("　")).isTrue();
        assertThat(TextRules.isBlank(null)).isTrue();
        assertThat(TextRules.isBlank(" x ")).isFalse();
    }

    @Test
    @DisplayName("字數以 code point 計（emoji 1 個；與 PostgreSQL char_length 一致），strip 後再計")
    void codePoints() {
        String emoji = "😀"; // 1 code point, 2 UTF-16 units
        assertThat(emoji.length()).isEqualTo(2);
        assertThat(TextRules.codePointLength(emoji)).isEqualTo(1);
        assertThat(TextRules.strippedLength("　" + emoji + "ab ")).isEqualTo(3);
        assertThat(TextRules.codePointLength(null)).isZero();
    }

    static class Form {
        @NotNull
        @CodePointLength(min = 1, max = 3)
        String required;

        @CodePointLength(max = 2, strip = false)
        String raw;

        Form(String required, String raw) {
            this.required = required;
            this.raw = raw;
        }
    }

    @Test
    @DisplayName("Bean Validation @CodePointLength：strip 後 1–max、原文計、null 交給 @NotNull")
    void beanValidation() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        String emoji = "😀";
        assertThat(validator.validate(new Form(emoji + emoji + emoji, null))).isEmpty();
        assertThat(validator.validate(new Form(" " + emoji + "ab　", null))).isEmpty();
        assertThat(validator.validate(new Form("abcd", null))).hasSize(1);
        assertThat(validator.validate(new Form("　 ", null))).hasSize(1);
        assertThat(validator.validate(new Form(null, null))).hasSize(1);
        assertThat(validator.validate(new Form("a", " a "))).hasSize(1);
        assertThat(validator.validate(new Form("a", emoji + emoji))).isEmpty();
    }
}
