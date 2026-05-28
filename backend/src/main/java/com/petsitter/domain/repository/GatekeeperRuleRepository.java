package com.petsitter.domain.repository;

import com.petsitter.domain.model.GatekeeperRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GatekeeperRuleRepository extends JpaRepository<GatekeeperRule, UUID> {
    List<GatekeeperRule> findBySitterId(UUID sitterId);
    
    List<GatekeeperRule> findBySitterIdAndTargetUserId(UUID sitterId, UUID targetUserId);

    boolean existsBySitterIdAndTargetUserIdAndScopeTypeAndRuleTypeIn(
            UUID sitterId, UUID targetUserId, String scopeType, List<String> ruleTypes);

    boolean existsBySitterIdAndPlanIdAndTargetUserIdAndScopeTypeAndRuleTypeIn(
            UUID sitterId, UUID planId, UUID targetUserId, String scopeType, List<String> ruleTypes);

    Optional<GatekeeperRule> findBySitterIdAndTargetUserIdAndScopeTypeAndRuleType(
            UUID sitterId, UUID targetUserId, String scopeType, String ruleType);

    Optional<GatekeeperRule> findBySitterIdAndPlanIdAndTargetUserIdAndScopeTypeAndRuleType(
            UUID sitterId, UUID planId, UUID targetUserId, String scopeType, String ruleType);
}
