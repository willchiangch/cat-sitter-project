package com.petsitter.application.service.order;

import com.petsitter.domain.model.order.ActorRole;

import java.util.UUID;

/** 操作者（{@code order_logs.operator_role} / {@code operator_user_id}）；SYSTEM 的 userId 為 null。 */
public record Actor(ActorRole role, UUID userId) {

    public static final Actor SYSTEM = new Actor(ActorRole.SYSTEM, null);

    public Actor {
        if (role == null) {
            throw new IllegalArgumentException("role");
        }
        if (role == ActorRole.SYSTEM && userId != null) {
            throw new IllegalArgumentException("SYSTEM actor has no userId");
        }
    }

    public static Actor owner(UUID userId) {
        return new Actor(ActorRole.OWNER, userId);
    }

    public static Actor sitter(UUID userId) {
        return new Actor(ActorRole.SITTER, userId);
    }

    public static Actor admin(UUID userId) {
        return new Actor(ActorRole.ADMIN, userId);
    }
}
