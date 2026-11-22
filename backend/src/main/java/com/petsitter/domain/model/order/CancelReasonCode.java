package com.petsitter.domain.model.order;

/** 取消原因碼（ck_orders_cancel_code、SD-ORDER §6.3 E2 cancellation.reasonCode）。 */
public enum CancelReasonCode {
    OWNER_WITHDRAWN, SITTER_DECLINED, REVIEW_EXPIRED, SITTER_CANCELLED, PAYMENT_EXPIRED, ADMIN_VOIDED, MODIFICATION, ADMIN_MEDIATED
}
