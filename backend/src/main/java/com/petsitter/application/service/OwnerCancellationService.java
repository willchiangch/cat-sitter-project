package com.petsitter.application.service;

import com.petsitter.domain.event.OrderCancelledByOwnerEvent;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.OrderLog;
import com.petsitter.domain.model.Visit;
import com.petsitter.domain.repository.OrderLogRepository;
import com.petsitter.domain.repository.OrderRepository;
import com.petsitter.domain.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 飼主撤單（PRD-006 例外處理「飼主在保母審核中嘗試取消」，fix-batch10 H5）。
 *
 * <p>只限付款前：訂單 PENDING（保母評估中），或 PENDING_PAYMENT 且尚未上傳付款憑證、且不是訂單變更後的補款階段。
 * 已付款之後（PAID / CONFIRMED / IN_PROGRESS …）、補款階段一律 409，取消請走訂單變更協商（PRD-016）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OwnerCancellationService {

    static final String CANCELLED_BY_OWNER = "OWNER";
    static final String MSG_NEGOTIATE = "如需取消請透過「申請訂單變更」與保母協商（PRD-016）";

    private final OrderRepository orderRepository;
    private final VisitRepository visitRepository;
    private final OrderLogRepository orderLogRepository;
    private final SupplementPaymentResolver supplementPaymentResolver;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 不可撤單的原因；可撤時回 empty。訂單詳情的 cancellableByOwner 與撤單 API 共用這一個判斷。
     */
    public Optional<String> rejectReason(Order order) {
        String status = order.getStatus();
        switch (status) {
            case "PENDING":
                return Optional.empty();
            case "PENDING_PAYMENT":
                // 補款階段代表首次付款已完成（變更只能從 CONFIRMED / IN_PROGRESS 發起），不能當成未付款撤單
                if (supplementPaymentResolver.findOpenSupplement(order).isPresent()) {
                    return Optional.of("訂單變更補款階段無法取消預約，" + MSG_NEGOTIATE);
                }
                if (order.getPaidAt() != null || hasText(order.getPaymentProofUrl())) {
                    return Optional.of("已上傳付款憑證，無法自行取消預約，" + MSG_NEGOTIATE);
                }
                return Optional.empty();
            case "CANCELLED":
                return Optional.of("此訂單已取消");
            case "COMPLETED":
                return Optional.of("此訂單已結案，無法取消");
            default:
                return Optional.of("訂單已付款或已進入服務 / 協商階段，無法直接取消預約，" + MSG_NEGOTIATE);
        }
    }

    public boolean isCancellableByOwner(Order order) {
        return rejectReason(order).isEmpty();
    }

    @Transactional
    public void cancelByOwner(UUID ownerId, UUID orderId, int expectedVersion) {
        log.info("[OwnerCancellation] Owner {} cancelling order {}", ownerId, orderId);

        // 與保母接單 / 報價（同樣走 findByIdForUpdate）搶同一把列鎖，跨角色併發只會有一方成功
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalArgumentException("找不到對應的訂單: " + orderId));

        if (!order.getOwner().getId().equals(ownerId)) {
            log.error("BOLA Violation: Owner {} tried to cancel order {} owned by {}",
                    ownerId, orderId, order.getOwner().getId());
            throw new AccessDeniedException("權限不足：您非此訂單的飼主");
        }

        Optional<String> reason = rejectReason(order);
        if (reason.isPresent()) {
            throw new IllegalStateException(reason.get());
        }

        // 飼主看到的版本已過期（例如保母剛接單 / 報價）：要求重新整理後再決定
        if (!order.getVersion().equals(expectedVersion)) {
            throw new ObjectOptimisticLockingFailureException(Order.class, orderId);
        }

        String previousStatus = order.getStatus();
        order.setStatus("CANCELLED");
        order.setCancelledBy(CANCELLED_BY_OWNER);
        order.setWaitingForOwnerAction(false);
        orderRepository.save(order);

        List<Visit> visits = visitRepository.findByOrderId(orderId);
        visits.forEach(v -> v.setStatus("CANCELLED"));
        visitRepository.saveAll(visits);

        // 同一交易寫入：AuditLogService.writeOrderLog 是 REQUIRES_NEW，新交易插 order_logs 的 FK 檢查
        // 要等這裡 FOR UPDATE 的列鎖，會互等到逾時
        orderLogRepository.save(OrderLog.builder()
                .order(order)
                .operatorId(ownerId.toString())
                .actionType("ORDER_CANCELLED_BY_OWNER")
                .payload(Map.of("previousStatus", previousStatus))
                .build());

        eventPublisher.publishEvent(new OrderCancelledByOwnerEvent(orderId, ownerId, previousStatus));
        log.info("[OwnerCancellation] Order {} cancelled by owner {} (was {})", orderId, ownerId, previousStatus);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
