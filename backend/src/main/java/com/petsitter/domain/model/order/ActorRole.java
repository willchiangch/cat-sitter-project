package com.petsitter.domain.model.order;

/** 操作者角色（order_logs.operator_role、orders.cancelled_by / completed_by 的值域來源）。 */
public enum ActorRole {
    OWNER, SITTER, ADMIN, SYSTEM
}
