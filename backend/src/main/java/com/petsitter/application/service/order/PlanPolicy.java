package com.petsitter.application.service.order;

import com.petsitter.application.exception.OrderErrorCode;
import com.petsitter.application.exception.OrderException;
import com.petsitter.application.port.SubscriptionQuery;
import com.petsitter.application.port.SubscriptionQuery.SubscriptionStatus;
import com.petsitter.infrastructure.security.gating.PlanTier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 方案判斷（SD-ORDER §2.4.2 末段、§11.2 決策 5）：包裝 {@link SubscriptionQuery}，由 Service 在判斷順序第 13 步
 * （或業務規則需要的位置）呼叫；不使用 {@code @RequirePlan}。
 */
@Component
@RequiredArgsConstructor
public class PlanPolicy {

    public enum PricingMode { SYSTEM_ONLY, CUSTOM_ALLOWED }

    public static final int UNLIMITED = Integer.MAX_VALUE;

    private final SubscriptionQuery subscriptionQuery;

    public SubscriptionStatus status(UUID sitterId) {
        return subscriptionQuery.current(sitterId);
    }

    public boolean adjustmentAllowed(SubscriptionStatus s) {
        return !s.locked() && s.tier().isAtLeast(PlanTier.PRO);
    }

    /** FREE / BASIC 或鎖定 → 403 {@code PLAN_REQUIRED}。 */
    public void requireAdjustmentAllowed(UUID sitterId) {
        if (!adjustmentAllowed(status(sitterId))) {
            throw new OrderException(OrderErrorCode.PLAN_REQUIRED);
        }
    }

    /** 變更計價模式（鎖定時一律 SYSTEM_ONLY，CQ-66）。 */
    public PricingMode modificationPricingMode(UUID sitterId) {
        return adjustmentAllowed(status(sitterId)) ? PricingMode.CUSTOM_ALLOWED : PricingMode.SYSTEM_ONLY;
    }

    public void requireNotLocked(UUID sitterId) {
        if (status(sitterId).locked()) {
            throw new OrderException(OrderErrorCode.SUBSCRIPTION_LOCKED);
        }
    }

    /** 每月接單上限（FREE 3、BASIC 20、PRO / ULTIMATE 無限；baseline §2.1）。 */
    public int quotaLimit(UUID sitterId) {
        return quotaLimit(status(sitterId).tier());
    }

    public static int quotaLimit(PlanTier tier) {
        return switch (tier) {
            case FREE -> 3;
            case BASIC -> 20;
            default -> UNLIMITED;
        };
    }

    /** 方案可預約區間只在 PRO / ULTIMATE 且未鎖定時套用（baseline §2.1、CQ-68）。 */
    public static boolean bookableRangeApplies(SubscriptionStatus s) {
        return !s.locked() && s.tier().isAtLeast(PlanTier.PRO);
    }
}
