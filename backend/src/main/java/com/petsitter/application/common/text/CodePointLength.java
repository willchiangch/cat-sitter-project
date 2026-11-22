package com.petsitter.application.common.text;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean Validation：字串字數以 Unicode code point 計（SD-GLOBAL-SPEC §2.7）。
 * <p>
 * {@code strip = true}（預設）時先依 {@link TextRules#strip(String)} 去頭尾空白再計；{@code min = 1} 即「去空白後不可為空」。
 * {@code null} 視為合法（必填另加 {@code @NotNull}）。用法：
 * <pre>{@code
 * @NotNull @CodePointLength(min = 1, max = 500) String description;   // 必填，去空白後 1–500
 * @CodePointLength(max = 1000, strip = false) String text;            // 原文計，≤ 1000
 * }</pre>
 * 需遵守判斷順序的重建端點不掛 {@code @Valid}（GLOBAL §2.6），改由模組 validator 直接呼叫 {@link TextRules}。
 */
@Documented
@Constraint(validatedBy = CodePointLengthValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface CodePointLength {

    int min() default 0;

    int max() default Integer.MAX_VALUE;

    boolean strip() default true;

    String message() default "字數不符規定";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
