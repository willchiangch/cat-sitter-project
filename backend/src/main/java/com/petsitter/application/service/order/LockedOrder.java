package com.petsitter.application.service.order;

import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.OrderStatus;

import java.util.UUID;

/**
 * 已持有訂單鎖的唯讀檢視（SD-ORDER §2.4.1）：只暴露 {@code orderId}、{@code status}、{@code ownerId}、{@code sitterId}；
 * Entity 只在 order 套件內可取得，其他模組無法經由此檢視改訂單。
 * 只在發出它的交易內有效（{@link OrderLockService#requireHeld}）。
 */
public final class LockedOrder {

    private final Order order;
    private final Object transactionKey;

    LockedOrder(Order order, Object transactionKey) {
        this.order = order;
        this.transactionKey = transactionKey;
    }

    public UUID orderId() {
        return order.getId();
    }

    /** 目前主狀態（同交易內轉移後即為新值）。 */
    public OrderStatus status() {
        return order.getStatus();
    }

    public UUID ownerId() {
        return order.getOwnerId();
    }

    public UUID sitterId() {
        return order.getSitterId();
    }

    Order entity() {
        return order;
    }

    Object transactionKey() {
        return transactionKey;
    }

    @Override
    public String toString() {
        return "LockedOrder[" + orderId() + "," + status() + "]";
    }
}
