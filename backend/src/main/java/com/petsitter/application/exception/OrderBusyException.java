package com.petsitter.application.exception;

/**
 * 鎖等待逾時 / 死結（SQLSTATE 55P03 / 40P01）→ 409 {@code ORDER_BUSY} + {@code Retry-After: 1}
 * （SD-GLOBAL-SPEC §2.6、SD-ORDER §3.6.1 / §6.4）。可重試；冪等紀錄刪除。
 */
public class OrderBusyException extends RuntimeException {

    public OrderBusyException(Throwable cause) {
        super(CommonErrorCode.ORDER_BUSY.defaultMessage(), cause);
    }

    public OrderBusyException() {
        super(CommonErrorCode.ORDER_BUSY.defaultMessage());
    }
}
