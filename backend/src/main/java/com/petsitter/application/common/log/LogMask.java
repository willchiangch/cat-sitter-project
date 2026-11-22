package com.petsitter.application.common.log;

/**
 * log 遮蔽函式（SD-GLOBAL-SPEC §2.9、NFR-003 SEC-LOG-1、CQ-16）。
 * <ul>
 *   <li>email → 首字元 + {@code ***} + {@code @網域}（{@code w***@example.com}）；需要追查時改記 userId；</li>
 *   <li>收款帳號 → 銀行代碼 + 帳號後五碼（PRD-025 BR-8）；</li>
 *   <li>IP 可完整記錄（SEC-LOG-4 需要），不在此遮蔽。</li>
 * </ul>
 * CR / LF 由 logback {@code %replace} 統一跳脫（{@code logback-spring.xml}），本類別不重複處理。
 */
public final class LogMask {

    private LogMask() {
    }

    public static String email(String email) {
        if (email == null || email.isEmpty()) {
            return "";
        }
        int at = email.lastIndexOf('@');
        if (at <= 0) {
            return email.substring(0, Math.min(1, email.length())) + "***";
        }
        return email.substring(0, email.offsetByCodePoints(0, 1)) + "***" + email.substring(at);
    }

    /** 銀行代碼 + 帳號後五碼（例：{@code 812-*****90123}）。 */
    public static String bankAccount(String bankCode, String accountNumber) {
        String code = bankCode == null ? "" : bankCode;
        if (accountNumber == null || accountNumber.isEmpty()) {
            return code + "-";
        }
        int keep = Math.min(5, accountNumber.length());
        return code + "-*****" + accountNumber.substring(accountNumber.length() - keep);
    }
}
