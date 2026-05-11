package com.petsitter.application.service;

import com.petsitter.application.dto.QuoteRequest;
import com.petsitter.application.exception.AuthPlanLimitException;
import com.petsitter.application.exception.PricingMismatchException;
import com.petsitter.domain.model.*;
import com.petsitter.domain.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationService {

    private final OrderRepository orderRepository;
    private final OrderSnapshotRepository orderSnapshotRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final ServicePlanRepository servicePlanRepository;
    private final VisitRepository visitRepository;

    @Transactional
    public void sendQuote(UUID sitterId, UUID orderId, QuoteRequest request) {
        log.info("[EvaluationService] Sitter {} quoting for order {}", sitterId, orderId);

        // 1. 獲取訂單與 BOLA / 狀態檢查
        Order order = orderRepository.findByIdAndSitterId(orderId, sitterId)
                .orElseThrow(() -> new IllegalArgumentException("找不到該保母的指定訂單: " + orderId));

        if (!"PENDING".equals(order.getStatus())) {
            throw new IllegalStateException("只有 PENDING 狀態的訂單可以報價");
        }

        // 2. 樂觀鎖檢查
        if (!order.getVersion().equals(request.getVersion())) {
            throw new IllegalStateException("訂單資料已被更新，請重新整理頁面後再報價 (Optimistic Lock Exception)");
        }

        // 3. Zero-Trust: 重新計算金額
        // 取得該保母目前的方案單價 (簡化版：取保母第一個方案)
        ServicePlan plan = servicePlanRepository.findAll().stream()
                .filter(p -> p.getSitter().getId().equals(sitterId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("保母未設定服務方案"));

        long visitCount = visitRepository.findAll().stream()
                .filter(v -> v.getOrder().getId().equals(orderId))
                .count();

        BigDecimal unitPrice = BigDecimal.valueOf(plan.getPrice());
        BigDecimal count = BigDecimal.valueOf(visitCount);
        
        int serverOriginalTotal = unitPrice.multiply(count).setScale(0, RoundingMode.HALF_UP).intValue();
        int serverFinalTotal = serverOriginalTotal + request.getAdjustmentAmount();

        if (serverFinalTotal != request.getExpectedTotalAmount()) {
            log.error("Pricing mismatch! Server calculated: {}, Client expected: {}", serverFinalTotal, request.getExpectedTotalAmount());
            throw new PricingMismatchException("報價金額校驗失敗，可能方案價格已變更");
        }

        // 4. SaaS Gating: 只有 PRO 以上可以調價
        if (request.getAdjustmentAmount() != 0) {
            Subscription sub = subscriptionRepository.findBySitterId(sitterId)
                    .orElseThrow(() -> new AuthPlanLimitException("找不到保母訂閱資訊"));
            
            String tier = sub.getPlanTier().toUpperCase();
            if (!"PRO".equals(tier) && !"ULTIMATE".equals(tier)) {
                throw new AuthPlanLimitException("非 PRO 或 ULTIMATE 方案保母不可進行手動調價 (目前方案: " + tier + ")");
            }
        }

        // 5. 建立 Snapshot (SD-006)
        Map<String, Object> snapshotData = new HashMap<>();
        snapshotData.put("planName", plan.getName());
        snapshotData.put("unitPrice", plan.getPrice());
        snapshotData.put("visitCount", visitCount);

        OrderSnapshot snapshot = OrderSnapshot.builder()
                .order(order)
                .snapshotPlanTitle(plan.getName())
                .snapshotUnitPrice(plan.getPrice().intValue())
                .snapshotOriginalTotal(serverOriginalTotal)
                .adjustmentAmount(request.getAdjustmentAmount())
                .mediaRetentionDays(plan.getMediaRetentionDays())
                .maxPhotos(plan.getMaxPhotos())
                .maxVideoSeconds(plan.getMaxVideoSeconds())
                .snapshotData(snapshotData)
                .build();
        
        orderSnapshotRepository.save(snapshot);

        // 6. 更新訂單狀態與清理 Flag
        order.setStatus("PENDING_PAYMENT");
        order.setTotalAmount(serverFinalTotal);
        order.setAdjustmentAmount(request.getAdjustmentAmount());
        order.setAdjustmentReason(request.getAdjustmentReason());
        order.setWaitingForOwnerAction(false);
        
        orderRepository.save(order);

        log.info("[EvaluationService] Quote successfully sent for order {}. Total: {}", orderId, serverFinalTotal);
    }
}
