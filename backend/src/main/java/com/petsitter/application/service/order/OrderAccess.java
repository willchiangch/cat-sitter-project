package com.petsitter.application.service.order;

import com.petsitter.application.exception.OrderException;
import com.petsitter.domain.model.order.ActorRole;
import com.petsitter.domain.model.order.Order;

/**
 * 歸屬檢查（SD-ORDER §6.1 G4a / G5、§6.2）：非當事人 → 403 {@code FORBIDDEN}；當事人但 currentRole 不是其在此訂單的
 * 角色 → 403 {@code ROLE_MISMATCH}（G4a）。
 */
public final class OrderAccess {

    private OrderAccess() {
    }

    /** 混合角色端點：回傳呼叫者在此訂單的角色（OWNER / SITTER）。 */
    public static ActorRole partyRole(Order order, CurrentActor actor) {
        boolean isOwner = order.getOwnerId().equals(actor.userId());
        boolean isSitter = order.getSitterId().equals(actor.userId());
        if (!isOwner && !isSitter) {
            throw OrderException.forbidden();
        }
        if (isOwner && actor.isOwnerRole()) {
            return ActorRole.OWNER;
        }
        if (isSitter && actor.isSitterRole()) {
            return ActorRole.SITTER;
        }
        throw OrderException.roleMismatch();
    }

    /** 單一角色端點（G3 已由 @PreAuthorize 擋角色）：只檢查是否為該角色的當事人。 */
    public static void requireOwner(Order order, CurrentActor actor) {
        if (!order.getOwnerId().equals(actor.userId())) {
            throw OrderException.forbidden();
        }
    }

    public static void requireSitter(Order order, CurrentActor actor) {
        if (!order.getSitterId().equals(actor.userId())) {
            throw OrderException.forbidden();
        }
    }

    public static Actor toActor(ActorRole role, CurrentActor actor) {
        return new Actor(role, actor.userId());
    }
}
