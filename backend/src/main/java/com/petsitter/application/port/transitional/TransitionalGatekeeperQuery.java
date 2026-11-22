package com.petsitter.application.port.transitional;

import com.petsitter.application.port.GatekeeperQuery;
import com.petsitter.domain.model.GatekeeperRule;
import com.petsitter.domain.repository.GatekeeperRuleRepository;
import com.petsitter.infrastructure.security.gating.PlanTier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * SD-IDENTITY 未重建前的過渡實作（PRD-005 BR-6 / BR-7）：黑名單 PRO+ 生效、白名單 ULTIMATE 生效；
 * 匿名（viewerId = null）不在任何白名單內。{@code NO_QUESTIONNAIRE} 規則無效果（M23）。
 */
@Component
@RequiredArgsConstructor
public class TransitionalGatekeeperQuery implements GatekeeperQuery {

    private final GatekeeperRuleRepository repository;

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public GatekeeperResult evaluate(UUID sitterId, UUID viewerId, PlanTier tier) {
        boolean blackActive = tier.isAtLeast(PlanTier.PRO);
        boolean whiteActive = tier.isAtLeast(PlanTier.ULTIMATE);
        if (!blackActive) {
            return new GatekeeperResult(false, Set.of());
        }
        List<GatekeeperRule> rules = repository.findBySitterId(sitterId);
        boolean globallyBlocked = rules.stream().anyMatch(r -> global(r) && black(r) && Objects.equals(r.getTargetUserId(), viewerId));
        if (whiteActive) {
            boolean whiteMode = rules.stream().anyMatch(r -> global(r) && white(r));
            boolean inWhite = rules.stream().anyMatch(r -> global(r) && white(r) && Objects.equals(r.getTargetUserId(), viewerId));
            if (whiteMode && !inWhite) {
                globallyBlocked = true;
            }
        }
        Set<UUID> hidden = new HashSet<>();
        for (GatekeeperRule r : rules) {
            if (!"PLAN".equals(r.getScopeType()) || r.getPlanId() == null) {
                continue;
            }
            if (black(r) && Objects.equals(r.getTargetUserId(), viewerId)) {
                hidden.add(r.getPlanId());
            }
        }
        if (whiteActive) {
            Set<UUID> whitePlans = new HashSet<>();
            Set<UUID> viewerWhite = new HashSet<>();
            for (GatekeeperRule r : rules) {
                if ("PLAN".equals(r.getScopeType()) && r.getPlanId() != null && white(r)) {
                    whitePlans.add(r.getPlanId());
                    if (Objects.equals(r.getTargetUserId(), viewerId)) {
                        viewerWhite.add(r.getPlanId());
                    }
                }
            }
            whitePlans.removeAll(viewerWhite);
            hidden.addAll(whitePlans);
        }
        return new GatekeeperResult(globallyBlocked, Set.copyOf(hidden));
    }

    private static boolean global(GatekeeperRule r) {
        return "GLOBAL".equals(r.getScopeType());
    }

    private static boolean black(GatekeeperRule r) {
        return "BLACK".equals(r.getRuleType());
    }

    private static boolean white(GatekeeperRule r) {
        return "WHITE".equals(r.getRuleType());
    }
}
