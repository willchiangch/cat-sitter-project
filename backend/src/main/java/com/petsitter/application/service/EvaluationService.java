package com.petsitter.application.service;

import com.petsitter.application.dto.QuoteRequest;
import com.petsitter.application.dto.RejectOrderRequest;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationService {

    private final OrderRepository orderRepository;
    private final OrderSnapshotRepository orderSnapshotRepository;
    private final ServicePlanRepository servicePlanRepository;
    private final VisitRepository visitRepository;
    private final OrderLogRepository orderLogRepository;
    private final IdempotencyService idempotencyService;

    @RequirePlan(PlanTier.FREE)
    @Transactional
    public void sendQuote(UUID sitterId, UUID orderId, QuoteRequest request, String idempotencyKey) {
        log.info("[EvaluationService] Sitter {} quoting for order {}", sitterId, orderId);

        idempotencyService.checkAndConsume(idempotencyKey, sitterId);

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

        // 調價需要 PRO 以上方案的門檻，已由 PlanGatingAspect 攔截 @RequirePlan 方法時
        // 動態判讀 QuoteRequest.adjustmentAmount 處理，這裡不需要重複檢查

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

        orderLogRepository.save(OrderLog.builder()
                .order(order)
                .operatorId(sitterId.toString())
                .actionType("QUOTE_SENT")
                .payload(Map.of(
                        "adjustmentAmount", request.getAdjustmentAmount(),
                        "finalTotal", serverFinalTotal,
                        "adjustmentReason", request.getAdjustmentReason() == null ? "" : request.getAdjustmentReason()
                ))
                .build());

        log.info("[EvaluationService] Quote successfully sent for order {}. Total: {}", orderId, serverFinalTotal);
    }

    /**
     * 保母拒絕接單 (PRD-006 AC-4 / SD-006 §2.3)
     */
    @Transactional
    public void rejectOrder(UUID sitterId, UUID orderId, RejectOrderRequest request, String idempotencyKey) {
        log.info("[EvaluationService] Sitter {} rejecting order {}", sitterId, orderId);

        idempotencyService.checkAndConsume(idempotencyKey, sitterId);

        Order order = orderRepository.findByIdAndSitterId(orderId, sitterId)
                .orElseThrow(() -> new IllegalArgumentException("找不到該保母的指定訂單: " + orderId));

        if (!"PENDING".equals(order.getStatus())) {
            throw new IllegalStateException("只有 PENDING 狀態的訂單可以拒絕");
        }

        if (!order.getVersion().equals(request.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(Order.class, orderId);
        }

        order.setStatus("CANCELLED");
        order.setWaitingForOwnerAction(false);
        orderRepository.save(order);

        List<Visit> visits = visitRepository.findByOrderId(orderId);
        visits.forEach(v -> v.setStatus("CANCELLED"));
        visitRepository.saveAll(visits);

        orderLogRepository.save(OrderLog.builder()
                .order(order)
                .operatorId(sitterId.toString())
                .actionType("ORDER_REJECTED")
                .payload(Map.of("rejectReason", request.getRejectReason() == null ? "" : request.getRejectReason()))
                .build());

        log.info("[EvaluationService] Order {} rejected by sitter {}", orderId, sitterId);
    }
}
