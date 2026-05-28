package com.petsitter.application.service;

import com.petsitter.domain.model.GatekeeperRule;
import com.petsitter.domain.model.Subscription;
import com.petsitter.domain.model.User;
import com.petsitter.domain.model.UserActionLog;
import com.petsitter.domain.repository.GatekeeperRuleRepository;
import com.petsitter.domain.repository.SubscriptionRepository;
import com.petsitter.domain.repository.UserActionLogRepository;
import com.petsitter.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class GatekeeperService {

    private final GatekeeperRuleRepository gatekeeperRuleRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final UserActionLogRepository userActionLogRepository;

    @Transactional(readOnly = true)
    public List<GatekeeperRule> getRules(UUID sitterId) {
        return gatekeeperRuleRepository.findBySitterId(sitterId);
    }

    @Transactional
    public GatekeeperRule addRule(UUID sitterId, String targetEmail, String ruleType, String scopeType, UUID planId) {
        // 2. 查無帳號防禦
        User targetUser = userRepository.findByEmail(targetEmail)
                .orElseThrow(() -> {
                    log.warn("[GatekeeperService] Failed to add rule: email {} not found for sitterId {}", targetEmail, sitterId);
                    return new IllegalArgumentException("查無此 Email 帳號");
                });

        UUID targetUserId = targetUser.getId();

        // 3. 互斥與重複性檢查
        if ("BLACK".equals(ruleType) || "WHITE".equals(ruleType)) {
            String mutualType = "BLACK".equals(ruleType) ? "WHITE" : "BLACK";
            boolean conflictExists;
            if ("GLOBAL".equals(scopeType)) {
                conflictExists = gatekeeperRuleRepository
                        .existsBySitterIdAndTargetUserIdAndScopeTypeAndRuleTypeIn(
                                sitterId, targetUserId, "GLOBAL", Arrays.asList(mutualType));
            } else {
                conflictExists = gatekeeperRuleRepository
                        .existsBySitterIdAndPlanIdAndTargetUserIdAndScopeTypeAndRuleTypeIn(
                                sitterId, planId, targetUserId, "PLAN", Arrays.asList(mutualType));
            }
            if (conflictExists) {
                log.warn("[GatekeeperService] Mutual exclusion conflict: targetUserId {} cannot be both BLACK and WHITE in same scope {} for sitterId {}", targetUserId, scopeType, sitterId);
                throw new IllegalArgumentException("同一對象在同範圍內不能同時並存於黑名單與白名單中");
            }
        }

        // 4. 重複規則檢查
        boolean duplicateExists;
        if ("GLOBAL".equals(scopeType)) {
            duplicateExists = gatekeeperRuleRepository
                    .existsBySitterIdAndTargetUserIdAndScopeTypeAndRuleTypeIn(
                            sitterId, targetUserId, "GLOBAL", Arrays.asList(ruleType));
        } else {
            duplicateExists = gatekeeperRuleRepository
                    .existsBySitterIdAndPlanIdAndTargetUserIdAndScopeTypeAndRuleTypeIn(
                            sitterId, planId, targetUserId, "PLAN", Arrays.asList(ruleType));
        }
        if (duplicateExists) {
            log.warn("[GatekeeperService] Duplicate rule: targetUserId {} already has ruleType {} in scope {} for sitterId {}", targetUserId, ruleType, scopeType, sitterId);
            throw new IllegalArgumentException("規則已存在，請勿重複設定");
        }

        // 5. 儲存規則
        GatekeeperRule rule = GatekeeperRule.builder()
                .sitterId(sitterId)
                .ruleType(ruleType)
                .scopeType(scopeType)
                .planId(planId)
                .targetUserId(targetUserId)
                .build();

        GatekeeperRule savedRule = gatekeeperRuleRepository.save(rule);
        log.info("[GatekeeperService] Rule created successfully. ruleId: {}, sitterId: {}, targetUserId: {}, ruleType: {}, scopeType: {}",
                savedRule.getId(), sitterId, targetUserId, ruleType, scopeType);

        // 6. 寫入多租戶審計日誌
        UserActionLog auditLog = UserActionLog.builder()
                .funcCode("SITTER_GATEKEEPER_MGT")
                .actionType("CREATE")
                .actionResult("SUCCESS")
                .operatorId(sitterId)
                .targetId(savedRule.getId())
                .targetTable("gatekeeper_rules")
                .build();
        userActionLogRepository.save(auditLog);

        return savedRule;
    }

    @Transactional
    public void deleteRule(UUID sitterId, UUID ruleId) {
        GatekeeperRule rule = gatekeeperRuleRepository.findById(ruleId)
                .orElseThrow(() -> {
                    log.warn("[GatekeeperService] Delete rule failed: ruleId {} not found for sitterId {}", ruleId, sitterId);
                    return new IllegalArgumentException("找不到該門禁規則");
                });

        if (!rule.getSitterId().equals(sitterId)) {
            log.warn("[GatekeeperService] Access denied: sitterId {} attempted to delete ruleId {} owned by sitterId {}", sitterId, ruleId, rule.getSitterId());
            throw new AccessDeniedException("無權操作此規則");
        }

        gatekeeperRuleRepository.delete(rule);
        log.info("[GatekeeperService] Rule deleted successfully. ruleId: {}, sitterId: {}", ruleId, sitterId);

        // 寫入多租戶審計日誌
        UserActionLog auditLog = UserActionLog.builder()
                .funcCode("SITTER_GATEKEEPER_MGT")
                .actionType("DELETE")
                .actionResult("SUCCESS")
                .operatorId(sitterId)
                .targetId(ruleId)
                .targetTable("gatekeeper_rules")
                .build();
        userActionLogRepository.save(auditLog);
    }

    @Transactional(readOnly = true)
    public boolean isBlocked(UUID sitterId, UUID clientId, UUID planId) {
        Subscription sub = subscriptionRepository.findBySitterId(sitterId).orElse(null);
        if (sub == null) {
            return false;
        }

        boolean isPro = "PRO".equals(sub.getPlanTier()) || "ULTIMATE".equals(sub.getPlanTier());
        boolean isUltimate = "ULTIMATE".equals(sub.getPlanTier());
        boolean isExpired = sub.getExpiredAt() != null && sub.getExpiredAt().isBefore(OffsetDateTime.now());

        // 降級處理：若保母方案過期或不屬於 Pro/Ultimate，規則自動失效放行
        if (isExpired || (!isPro && !isUltimate)) {
            return false;
        }

        // 取得該 sitter 對 clientId 設下的所有門禁規則
        List<GatekeeperRule> rules = gatekeeperRuleRepository.findBySitterIdAndTargetUserId(sitterId, clientId);

        // 1. 全域黑名單卡控 (Deny-by-default: 黑名單優先級高於白名單)
        boolean hasGlobalBlack = rules.stream()
                .anyMatch(r -> "GLOBAL".equals(r.getScopeType()) && "BLACK".equals(r.getRuleType()));
        if (hasGlobalBlack) {
            return true;
        }

        // 2. 全域白名單卡控：若保母設定了任何 GLOBAL - WHITE 規則，表示開啟了全域白名單模式
        List<GatekeeperRule> allGlobalRules = gatekeeperRuleRepository.findBySitterId(sitterId);
        boolean hasGlobalWhiteMode = allGlobalRules.stream()
                .anyMatch(r -> "GLOBAL".equals(r.getScopeType()) && "WHITE".equals(r.getRuleType()));

        if (hasGlobalWhiteMode) {
            // 如果開了全域白名單模式，而客戶不在該 sitter 的全域白名單內，則封鎖
            boolean inGlobalWhite = rules.stream()
                    .anyMatch(r -> "GLOBAL".equals(r.getScopeType()) && "WHITE".equals(r.getRuleType()));
            if (!inGlobalWhite) {
                return true;
            }
        }

        // 3. 方案層級過濾卡控
        if (planId != null) {
            // 3.1 方案黑名單卡控
            boolean hasPlanBlack = rules.stream()
                    .anyMatch(r -> "PLAN".equals(r.getScopeType()) && planId.equals(r.getPlanId()) && "BLACK".equals(r.getRuleType()));
            if (hasPlanBlack) {
                return true;
            }

            // 3.2 方案白名單過濾：若該方案有設定任何 PLAN - WHITE 規則，表示該方案開啟了白名單模式
            boolean hasPlanWhiteMode = allGlobalRules.stream()
                    .anyMatch(r -> "PLAN".equals(r.getScopeType()) && planId.equals(r.getPlanId()) && "WHITE".equals(r.getRuleType()));

            if (hasPlanWhiteMode) {
                // 如果方案開啟了白名單模式，而客戶不在該方案的白名單內，則封鎖該方案
                boolean inPlanWhite = rules.stream()
                        .anyMatch(r -> "PLAN".equals(r.getScopeType()) && planId.equals(r.getPlanId()) && "WHITE".equals(r.getRuleType()));
                if (!inPlanWhite) {
                    return true;
                }
            }
        }

        return false;
    }

    @Transactional(readOnly = true)
    public boolean checkExemption(UUID sitterId, UUID clientId) {
        Subscription sub = subscriptionRepository.findBySitterId(sitterId).orElse(null);
        if (sub == null) {
            return false;
        }

        boolean isUltimate = "ULTIMATE".equals(sub.getPlanTier());
        boolean isExpired = sub.getExpiredAt() != null && sub.getExpiredAt().isBefore(OffsetDateTime.now());

        // 訂閱過期或非頂級版，免填問卷卡控自動失效
        if (isExpired || !isUltimate) {
            return false;
        }

        List<GatekeeperRule> rules = gatekeeperRuleRepository.findBySitterIdAndTargetUserId(sitterId, clientId);
        return rules.stream().anyMatch(r -> "NO_QUESTIONNAIRE".equals(r.getRuleType()));
    }
}
