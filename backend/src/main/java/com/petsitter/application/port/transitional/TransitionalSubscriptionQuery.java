package com.petsitter.application.port.transitional;

import com.petsitter.application.port.SubscriptionQuery;
import com.petsitter.domain.model.Subscription;
import com.petsitter.domain.repository.ProfileRepository;
import com.petsitter.domain.repository.SubscriptionRepository;
import com.petsitter.infrastructure.security.gating.PlanTier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * SD-BILLING 未重建前的過渡實作：等級取 {@code subscriptions.plan_tier}（無訂閱 = FREE）；
 * 鎖定 = 到期日（Asia/Taipei）隔天 00:00 起，或 ADMIN 停權（{@code profiles.kyc_status = SUSPENDED}，PRD-005 BR-10）。
 */
@Component
@RequiredArgsConstructor
public class TransitionalSubscriptionQuery implements SubscriptionQuery {

    static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final SubscriptionRepository subscriptionRepository;
    private final ProfileRepository profileRepository;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public SubscriptionStatus current(UUID sitterId) {
        Subscription sub = subscriptionRepository.findBySitterId(sitterId).orElse(null);
        PlanTier tier = sub == null ? PlanTier.FREE : PlanTier.fromString(sub.getPlanTier());
        boolean expired = false;
        if (sub != null && sub.getExpiredAt() != null) {
            OffsetDateTime lockAt = sub.getExpiredAt().atZoneSameInstant(TAIPEI).toLocalDate().plusDays(1)
                    .atStartOfDay(TAIPEI).toOffsetDateTime();
            expired = !OffsetDateTime.now(clock).isBefore(lockAt);
        }
        boolean suspended = profileRepository.findByUserIdAndType(sitterId, "SITTER")
                .map(p -> "SUSPENDED".equals(p.getKycStatus())).orElse(false);
        return new SubscriptionStatus(tier, expired || suspended);
    }
}
