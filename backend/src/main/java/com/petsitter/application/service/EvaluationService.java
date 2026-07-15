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

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationService {

    private final OrderRepository orderRepository;
    private final OrderSnapshotRepository orderSnapshotRepository;
    private final ServicePlanRepository servicePlanRepository;

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

        // 訂單建立時 (BookingService.createBooking) 已依實際預約明細算好正確總額，
        // 報價階段只需在這個基準上疊加保母的調整金額，不應該再抓「保母隨便一個方案」的單價重算，
        // 否則多方案訂單、或保母有多個方案時，金額會整個算錯
        int serverOriginalTotal = order.getTotalAmount();
        int serverFinalTotal = serverOriginalTotal + request.getAdjustmentAmount();

        if (serverFinalTotal < 0) {
            throw new IllegalArgumentException("報價調整後總額不可為負數");
        }

        if (serverFinalTotal != request.getExpectedTotalAmount()) {
            log.error("Pricing mismatch! Server calculated: {}, Client expected: {}", serverFinalTotal, request.getExpectedTotalAmount());
            throw new PricingMismatchException("報價金額校驗失敗，可能方案價格已變更");
        }

        // 多方案訂單以下單時的第一個方案為準 (沿用 BookingService.createBooking 的既有慣例)，
        // 媒體保留規則快照也以這個主要方案為準
        ServicePlan plan = servicePlanRepository.findById(order.getPlanId())
                .orElseThrow(() -> new IllegalStateException("找不到訂單對應的服務方案"));

        // --- 建立 Snapshot (SD-006) ---
        OrderSnapshot snapshot = OrderSnapshot.builder()
                .order(order)
                .snapshotPlanTitle(plan.getName())
                .snapshotUnitPrice(plan.getPrice().intValue())
                .snapshotOriginalTotal(serverOriginalTotal)
                .adjustmentAmount(request.getAdjustmentAmount())
                .mediaRetentionDays(plan.getMediaRetentionDays())
                .maxPhotos(plan.getMaxPhotos())
                .maxVideos(OrderSnapshot.DEFAULT_MAX_VIDEOS)
                .maxVideoSeconds(plan.getMaxVideoSeconds())
                .snapshotData(order.getItems())
                .build();

        orderSnapshotRepository.save(snapshot);

        order.setStatus("PENDING_PAYMENT");
        order.setTotalAmount(serverFinalTotal);
        order.setAdjustmentAmount(request.getAdjustmentAmount());
        order.setAdjustmentReason(request.getAdjustmentReason());
        order.setWaitingForOwnerAction(false);
        // 保留下單時的原始 items（內含每個方案各自的 dates/timesPerDay/petIds），不再用單一假項目覆蓋掉

        orderRepository.save(order);

        log.info("[EvaluationService] Quote successfully sent for order {}. Total: {}", orderId, serverFinalTotal);
    }
}
