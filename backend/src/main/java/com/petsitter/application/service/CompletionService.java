package com.petsitter.application.service;

import com.petsitter.application.dto.AdminResolveRequest;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.OrderLog;
import com.petsitter.domain.model.Visit;
import com.petsitter.domain.repository.OrderLogRepository;
import com.petsitter.domain.repository.OrderRepository;
import com.petsitter.domain.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompletionService {

    private final OrderRepository orderRepository;
    private final VisitRepository visitRepository;
    private final OrderLogRepository orderLogRepository;

    /**
     * 管理員裁決與強制結案 (SD-009 Scenario 2)
     */
    @Transactional
    public void resolveDisputedOrder(UUID orderId, AdminResolveRequest request) {
        log.info("[CompletionService] Admin resolving disputed order: {}", orderId);
        
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("找不到訂單: " + orderId));
        
        // 1. 覆寫金額與狀態
        order.setTotalAmount(request.getFinalTotalAmount());
        order.setStatus("COMPLETED");
        order.setCompletedAt(OffsetDateTime.now());
        order.setPayoutAt(OffsetDateTime.now().plusDays(3));
        order.setDisputed(false);
        
        orderRepository.save(order);

        // 2. 寫入審計日誌 (Audit Trail)
        String operator = SecurityContextHolder.getContext().getAuthentication().getName();
        OrderLog logEntry = OrderLog.builder()
                .order(order)
                .operatorId(operator)
                .actionType("ADMIN_RESOLVED")
                .payload(Map.of(
                        "finalAmount", request.getFinalTotalAmount(),
                        "note", request.getResolutionNote(),
                        "evidence", request.getEvidenceUrl() != null ? request.getEvidenceUrl() : ""
                ))
                .build();
        
        orderLogRepository.save(logEntry);
        log.info("[CompletionService] Order {} resolved by admin {}", orderId, operator);
    }

    /**
     * 內部排程器呼叫的核心邏輯
     */
    @Transactional
    public void triggerAutoCompletion() {
        log.info("[CompletionService] Starting Auto-Completion Cron Job");

        // 1. 清理殭屍行程 (72 小時未打卡)
        OffsetDateTime zombieThreshold = OffsetDateTime.now().minusHours(72);
        List<Visit> zombieVisits = visitRepository.findByStatusAndScheduledAtBefore("PENDING", zombieThreshold);
        
        if (!zombieVisits.isEmpty()) {
            log.info("[CompletionService] Found {} zombie visits to close", zombieVisits.size());
            zombieVisits.forEach(v -> v.setStatus("CLOSED_BY_SYSTEM"));
            visitRepository.saveAll(zombieVisits);
        }

        // 2. 篩選符合結案條件的訂單 (最後一個行程結束過 48 小時)
        OffsetDateTime completionThreshold = OffsetDateTime.now().minusHours(48);
        List<Order> candidateOrders = orderRepository.findOrdersReadyForAutoComplete(completionThreshold);
        
        log.info("[CompletionService] Found {} orders candidates for auto-completion", candidateOrders.size());

        // 3. 逐筆處理結案 (獨立事務)
        for (Order order : candidateOrders) {
            try {
                processSingleOrderCompletion(order.getId());
            } catch (Exception e) {
                log.error("[CompletionService] Failed to auto-complete order {}: {}", order.getId(), e.getMessage());
            }
        }
    }

    /**
     * 手動結案入口
     */
    @Transactional
    public void manualComplete(UUID orderId, UUID ownerId) {
        log.info("[CompletionService] Manual completion requested for order: {}", orderId);
        
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("找不到訂單: " + orderId));
        
        if (!order.getOwner().getId().equals(ownerId)) {
            throw new IllegalArgumentException("無權操作此訂單");
        }

        if ("DISPUTED".equals(order.getStatus())) {
            throw new IllegalStateException("爭議中的訂單不可結案");
        }

        // 檢查是否所有行程皆已完成
        List<Visit> visits = visitRepository.findByOrderId(orderId);
        boolean allFinished = visits.stream()
                .allMatch(v -> List.of("DONE", "CLOSED_BY_SYSTEM").contains(v.getStatus()));
        
        if (!allFinished) {
            throw new IllegalStateException("尚有未完成的行程，不可結案");
        }

        completeOrder(order);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processSingleOrderCompletion(UUID orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        
        // 再次檢查狀態，防止併發衝突
        if ("DISPUTED".equals(order.getStatus())) {
            return;
        }

        log.info("[CompletionService] Auto-completing order: {}", orderId);
        completeOrder(order);
    }

    private void completeOrder(Order order) {
        OffsetDateTime now = OffsetDateTime.now();
        order.setStatus("COMPLETED");
        order.setCompletedAt(now);
        order.setPayoutAt(now.plusDays(3));
        
        orderRepository.save(order);
        log.info("[CompletionService] Order {} status transitioned to COMPLETED", order.getId());
    }
}
