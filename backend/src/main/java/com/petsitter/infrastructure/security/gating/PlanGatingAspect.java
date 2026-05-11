package com.petsitter.infrastructure.security.gating;

import com.petsitter.application.dto.QuoteRequest;
import com.petsitter.application.exception.AuthPlanLimitException;
import com.petsitter.domain.model.Subscription;
import com.petsitter.domain.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class PlanGatingAspect {

    private final SubscriptionRepository subscriptionRepository;

    @Before("@annotation(requirePlan)")
    public void checkPlan(JoinPoint joinPoint, RequirePlan requirePlan) {
        PlanTier requiredTier = requirePlan.value();
        
        // 1. 取得 Sitter ID (從參數中尋找)
        UUID sitterId = findSitterId(joinPoint);
        if (sitterId == null) {
            log.warn("[PlanGatingAspect] Cannot find sitterId in arguments, skipping gating check.");
            return;
        }

        // 2. 獲取目前方案
        Subscription sub = subscriptionRepository.findBySitterId(sitterId)
                .orElseThrow(() -> new AuthPlanLimitException("找不到保母訂閱資訊，無法驗證方案權限"));

        PlanTier currentTier = PlanTier.fromString(sub.getPlanTier());

        // 3. 特殊邏輯：對於報價請求，如果涉及調價，強制要求 PRO 方案
        Object[] args = joinPoint.getArgs();
        for (Object arg : args) {
            if (arg instanceof QuoteRequest quoteRequest) {
                if (quoteRequest.getAdjustmentAmount() != 0) {
                    requiredTier = PlanTier.PRO;
                    log.debug("[PlanGatingAspect] Adjustment detected, upgrading required tier to PRO");
                }
            }
        }

        // 4. 驗證權限
        if (!currentTier.isAtLeast(requiredTier)) {
            log.warn("[PlanGatingAspect] Access Denied: Sitter {} has {}, but {} is required", 
                    sitterId, currentTier.name(), requiredTier.name());
            
            String message = (requiredTier == PlanTier.PRO) 
                ? "當前方案不支援自訂報價 (需要專業方案以上)" 
                : String.format("權限不足：該功能需要 %s，您目前為 %s", requiredTier.getDisplayName(), currentTier.getDisplayName());
            
            throw new AuthPlanLimitException(message);
        }
        
        log.debug("[PlanGatingAspect] Access Granted: Sitter {} verified for {}", sitterId, requiredTier.name());
    }

    private UUID findSitterId(JoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        String[] paramNames = ((MethodSignature) joinPoint.getSignature()).getParameterNames();
        
        for (int i = 0; i < paramNames.length; i++) {
            if ("sitterId".equals(paramNames[i]) && args[i] instanceof UUID) {
                return (UUID) args[i];
            }
        }
        return null;
    }
}
