package com.petsitter.infrastructure.security.gating;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
public enum PlanTier {
    FREE(0, "免費方案", 7),
    BASIC(1, "基礎方案", 30),
    PRO(2, "專業方案", 90),
    ULTIMATE(3, "終極方案", -1);

    private final int level;
    private final String displayName;
    private final int mediaRetentionDays;

    PlanTier(int level, String displayName, int mediaRetentionDays) {
        this.level = level;
        this.displayName = displayName;
        this.mediaRetentionDays = mediaRetentionDays;
    }

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
