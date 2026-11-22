package com.petsitter.application.port;

import com.petsitter.infrastructure.security.gating.PlanTier;

import java.util.UUID;

/** 訂閱等級與鎖定即時查詢（SD-ORDER §2.4.2；SD-BILLING 實作，過渡）。 */
public interface SubscriptionQuery {

    SubscriptionStatus current(UUID sitterId);

    /** {@code locked} = 訂閱逾期（到期日隔天 00:00 Asia/Taipei 起）或 ADMIN 停權。 */
    record SubscriptionStatus(PlanTier tier, boolean locked) {
    }
}
