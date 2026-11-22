package com.petsitter.application.exception;

/**
 * 程式錯誤：傳入的 {@code LockedOrder} 不是同一交易內由 {@code OrderLockService} 發出的檢視（SD-ORDER §2.4.1）。
 * 刻意不繼承 {@link IllegalStateException}（全域對映為 409），落到未處理例外 → 500 {@code INTERNAL_ERROR}。
 */
public class OrderLockNotHeldException extends RuntimeException {
    public OrderLockNotHeldException(String message) {
        super(message);
    }
}
