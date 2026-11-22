package com.petsitter.application.port;

import com.petsitter.infrastructure.security.gating.PlanTier;

import java.util.Set;
import java.util.UUID;

/** 門禁名單（SD-ORDER §2.4.2；SD-IDENTITY 實作，過渡）。名單依 PRD-005 BR-6 / BR-7 只在對應等級生效。 */
public interface GatekeeperQuery {

    GatekeeperResult evaluate(UUID sitterId, UUID viewerId, PlanTier tier);

    record GatekeeperResult(boolean globallyBlocked, Set<UUID> hiddenPlanIds) {
    }
}
