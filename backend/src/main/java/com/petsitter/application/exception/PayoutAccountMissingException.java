package com.petsitter.application.exception;

/** E23 建補款時收款帳號快照取不到（資料異常，SD-ORDER §6.3 E23）→ 500、整筆回滾、冪等紀錄刪除。 */
public class PayoutAccountMissingException extends RuntimeException {
    public PayoutAccountMissingException(String message) {
        super(message);
    }
}
