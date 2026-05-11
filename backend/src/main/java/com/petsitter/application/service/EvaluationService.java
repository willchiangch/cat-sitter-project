package com.petsitter.application.service;

import com.petsitter.application.dto.QuoteRequest;
import com.petsitter.application.exception.PricingMismatchException;
import com.petsitter.domain.model.*;
import com.petsitter.domain.repository.*;
import com.petsitter.infrastructure.security.gating.PlanTier;
import com.petsitter.infrastructure.security.gating.RequirePlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationService {

    private final OrderRepository orderRepository;
    private final OrderSnapshotRepository orderSnapshotRepository;
    private final ServicePlanRepository servicePlanRepository;
    private final VisitRepository visitRepository;

    @RequirePlan(PlanTier.FREE)
    @Transactional
    public void sendQuote(UUID sitterId, UUID orderId, QuoteRequest request) {
        log.info("[EvaluationService] Sitter {} quoting for order {}", sitterId, orderId);

        Order order = orderRepository.findByIdAndSitterId(orderId, sitterId)
                .orElseThrow(() -> new IllegalArgumentException("找不到該保母的指定訂單: " + orderId));

        if (!"PENDING".equals(order.getStatus())) {
            throw new IllegalStateException("只有 PENDING 狀態的訂單可以報價");
        }

        if (!order.getVersion().equals(request.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(Order.class, orderId);
        }

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

        // --- 建立 Snapshot (SD-006) ---
        List<OrderItem> items = List.of(
                new OrderItem("SERVICE", plan.getName(), plan.getPrice().intValue(), (int) visitCount)
        );

        OrderSnapshot snapshot = OrderSnapshot.builder()
                .order(order)
                .snapshotPlanTitle(plan.getName())
                .snapshotUnitPrice(plan.getPrice().intValue())
                .snapshotOriginalTotal(serverOriginalTotal)
                .adjustmentAmount(request.getAdjustmentAmount())
                .mediaRetentionDays(plan.getMediaRetentionDays())
                .maxPhotos(plan.getMaxPhotos())
                .maxVideoSeconds(plan.getMaxVideoSeconds())
                .snapshotData(items)
                .build();
        
        orderSnapshotRepository.save(snapshot);

        order.setStatus("PENDING_PAYMENT");
        order.setTotalAmount(serverFinalTotal);
        order.setAdjustmentAmount(request.getAdjustmentAmount());
        order.setAdjustmentReason(request.getAdjustmentReason());
        order.setWaitingForOwnerAction(false);
        order.setItems(items); // 同步更新訂單項目
        
        orderRepository.save(order);

        log.info("[EvaluationService] Quote successfully sent for order {}. Total: {}", orderId, serverFinalTotal);
    }
}
