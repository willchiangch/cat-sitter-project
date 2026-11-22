package com.petsitter.application.common.text;

/**
 * 文字欄位的空白與字數（SD-GLOBAL-SPEC §2.7）。
 * <ul>
 *   <li>「空白」= {@link String#strip()}（{@link Character#isWhitespace(int)}，含 U+3000、Tab、換行）。
 *       不得用 {@link String#trim()}（只去 ≤ U+0020）。</li>
 *   <li>另加 U+00A0（不換行空白）：JDK 的 {@code Character.isWhitespace} 不含它，GLOBAL §2.7 明定納入（2026-11-22 修訂）。</li>
 *   <li>字數 = strip 後的 Unicode code point 數（與 PostgreSQL {@code char_length} 一致）。</li>
 *   <li>儲存 strip 後的值；strip 後為空：必填 → 400，選填 → NULL（{@link #stripToNull(String)}）。</li>
 * </ul>
 */
public final class TextRules {

    private TextRules() {
    }

    /** GLOBAL §2.7 的空白判定。 */
    public static boolean isBlankChar(int codePoint) {
        return Character.isWhitespace(codePoint) || codePoint == 0x00A0;
    }

    /** 去頭尾空白；null 回 null。 */
    public static String strip(String value) {
        if (value == null) {
            return null;
        }
        int start = 0;
        int end = value.length();
        while (start < end) {
            int cp = value.codePointAt(start);
            if (!isBlankChar(cp)) {
                break;
            }
            start += Character.charCount(cp);
        }
        while (end > start) {
            int cp = value.codePointBefore(end);
            if (!isBlankChar(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return value.substring(start, end);
    }

    /** 選填欄位：strip 後為空 → null。 */
    public static String stripToNull(String value) {
        String stripped = strip(value);
        return (stripped == null || stripped.isEmpty()) ? null : stripped;
    }

    /** strip 後是否為空（null 視為空）。 */
    public static boolean isBlank(String value) {
        String stripped = strip(value);
        return stripped == null || stripped.isEmpty();
    }

    /** 原文的 code point 數；null 回 0。 */
    public static int codePointLength(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    /** strip 後的 code point 數；null 回 0。 */
    public static int strippedLength(String value) {
        return codePointLength(strip(value));
    }
}
