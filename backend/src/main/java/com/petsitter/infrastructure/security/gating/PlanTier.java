package com.petsitter.infrastructure.security.gating;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PlanTier {
    FREE(0, "免費方案"),
    BASIC(1, "基礎方案"),
    PRO(2, "專業方案"),
    ULTIMATE(3, "終極方案");

    private final int level;
    private final String displayName;

    public static PlanTier fromString(String tier) {
        try {
            return PlanTier.valueOf(tier.toUpperCase());
        } catch (Exception e) {
            return FREE;
        }
    }

    public boolean isAtLeast(PlanTier required) {
        return this.level >= required.getLevel();
    }
}
