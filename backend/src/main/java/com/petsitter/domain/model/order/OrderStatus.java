package com.petsitter.domain.model.order;

/** 訂單主狀態（baseline §3.1；ck_orders_status）。 */
public enum OrderStatus {
    PENDING, PENDING_PAYMENT, CONFIRMED, IN_PROGRESS, DISPUTED, COMPLETED, CANCELLED
}
