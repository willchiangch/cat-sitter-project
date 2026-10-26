package com.petsitter.application.service;

import com.petsitter.application.dto.QuoteRequest;
import com.petsitter.application.dto.RejectOrderRequest;
import com.petsitter.application.exception.PricingMismatchException;
import com.petsitter.domain.event.OrderRejectedBySitterEvent;
import com.petsitter.domain.model.*;
import com.petsitter.domain.repository.*;
import com.petsitter.infrastructure.security.gating.PlanTier;
import com.petsitter.infrastructure.security.gating.RequirePlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;

    @RequirePlan(PlanTier.FREE)
    @Transactional
    public void sendQuote(UUID sitterId, UUID orderId, QuoteRequest request, String idempotencyKey) {
        log.info("[EvaluationService] Sitter {} quoting for order {}", sitterId, orderId);

        idempotencyService.checkAndConsume(idempotencyKey, sitterId);

        // 鎖住訂單列：與原價接單 ConfirmOrderService 同一張單併發時，後到者讀到 PENDING_PAYMENT → 409
        // fix-batch11 I1：找得到訂單但不是自己的 → 403（過去 findByIdAndSitterId 查不到一律 400，分不出越權）
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalArgumentException("找不到指定訂單: " + orderId));
        if (!order.getSitter().getId().equals(sitterId)) {
            throw new org.springframework.security.access.AccessDeniedException("權限不足：您非此訂單的保母");
        }

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

        // fix-batch11 I1：找得到訂單但不是自己的 → 403
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("找不到指定訂單: " + orderId));
        if (!order.getSitter().getId().equals(sitterId)) {
            throw new org.springframework.security.access.AccessDeniedException("權限不足：您非此訂單的保母");
        }

        if (!"PENDING".equals(order.getStatus())) {
            throw new IllegalStateException("只有 PENDING 狀態的訂單可以拒絕");
        }

        if (!order.getVersion().equals(request.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(Order.class, orderId);
        }

        String rejectReason = request.getRejectReason() == null ? "" : request.getRejectReason().trim();

        order.setStatus("CANCELLED");
        // fix-batch13 K1：記錄取消者，飼主詳情據此顯示「保母已婉拒此預約」與原因（過去保母婉拒為 NULL，與其他取消分不出來）
        order.setCancelledBy(Order.CANCELLED_BY_SITTER);
        order.setWaitingForOwnerAction(false);
        orderRepository.save(order);

        List<Visit> visits = visitRepository.findByOrderId(orderId);
        visits.forEach(v -> v.setStatus("CANCELLED"));
        visitRepository.saveAll(visits);

        orderLogRepository.save(OrderLog.builder()
                .order(order)
                .operatorId(sitterId.toString())
                .actionType("ORDER_REJECTED")
                .payload(Map.of("rejectReason", rejectReason))
                .build());

        // PRD-006 D：通知飼主「預約已婉拒」（含原因）。AFTER_COMMIT 監聽，交易回滾（版本過期、併發）時不會發出。
        // 過去只寫日誌，評估頁確認框卻寫「拒絕後飼主將收到通知」（驗收 20261026-0007 截圖新問題 1）
        eventPublisher.publishEvent(new OrderRejectedBySitterEvent(orderId, sitterId, rejectReason));

        log.info("[EvaluationService] Order {} rejected by sitter {}", orderId, sitterId);
    }
}
