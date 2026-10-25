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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
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
    private final AuthService authService;

    /**
     * 內部排程 API 觸發點 (SD-009 Scenario 1)
     */
    @Transactional
    public void triggerAutoCompletion() {
        log.info("Starting auto-completion cron job...");
        OffsetDateTime now = OffsetDateTime.now();

        // 1. 清理 72 小時未打卡的殭屍行程
        OffsetDateTime zombieThreshold = now.minusHours(72);
        List<Visit> zombieVisits = visitRepository.findByStatusAndScheduledAtBefore("PENDING", zombieThreshold);
        for (Visit v : zombieVisits) {
            v.setStatus("CLOSED_BY_SYSTEM");
            log.info("Visit {} closed by system (Zombie)", v.getId());
        }
        visitRepository.saveAll(zombieVisits);

        // 2. 結案 48 小時無異議的訂單
        OffsetDateTime completionThreshold = now.minusHours(48);
        List<Order> inProgressOrders = orderRepository.findByStatus("IN_PROGRESS");
        
        for (Order order : inProgressOrders) {
            List<Visit> visits = visitRepository.findByOrderId(order.getId());

            // 零行程訂單不自動結案（與 manualComplete 的 N10 檢查一致；fix-batch10 H3）：allMatch 對空集合回 true、
            // lastVisitTime 退回 OffsetDateTime.MIN 必定早於門檻，過去會把「沒有任何服務」的訂單直接結案放款。
            // 留 WARN log 供人工處理（例如訂單變更把行程全刪掉、或預約 dates 為空的異常單）
            if (visits.isEmpty()) {
                log.warn("[AutoCompletion] Order {} ({}) has no visits, skipped auto-completion; needs manual review",
                        order.getId(), order.getStatus());
                continue;
            }

            // 檢查是否所有行程都已結束 (打卡或被系統關閉)
            boolean allFinished = visits.stream().allMatch(v -> 
                "DONE".equals(v.getStatus()) || "CLOSED_BY_SYSTEM".equals(v.getStatus()));
            
            if (allFinished) {
                // 找出最後一個行程的時間 (如果有完成時間用完成時間，否則用排程時間)
                OffsetDateTime lastVisitTime = visits.stream()
                    .map(v -> v.getFinishedAt() != null ? v.getFinishedAt() : v.getScheduledAt())
                    .max(OffsetDateTime::compareTo)
                    .orElse(OffsetDateTime.MIN);

                // 若最後行程經過 48 小時，觸發結案
                if (lastVisitTime.isBefore(completionThreshold)) {
                    order.setStatus("COMPLETED");
                    order.setCompletedAt(now);
                    order.setPayoutAt(now.plusDays(3)); // 財務撥款基準：結案後 3 天
                    orderRepository.save(order);
                    
                    // 寫入系統自動結案日誌
                    writeAuditLog(order.getId(), "SYSTEM_CRON", "AUTO_COMPLETED", Map.of());
                    log.info("Order {} auto-completed", order.getId());
                }
            }
        }
    }

    /**
     * 管理員爭議裁決 (SD-009 Scenario 2)
     */
    @Transactional
    public void resolveDisputedOrder(UUID orderId, AdminResolveRequest req) {
        log.info("[CompletionService] Admin resolving disputed order: {}", orderId);

        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        if (!"DISPUTED".equals(order.getStatus())) {
            throw new IllegalStateException("Only DISPUTED orders can be resolved");
        }

        String adminEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        if (!authService.verifyPassword(adminEmail, req.adminPassword())) {
            // 故意用 403 而非 401：前端 axiosClient 對所有 401 都會觸發全域的
            // refresh-token 靜默重試機制，會把「二次驗證密碼錯誤」誤判成 session 過期
            throw new AccessDeniedException("二次驗證密碼錯誤");
        }

        OffsetDateTime now = OffsetDateTime.now();
        order.setStatus("COMPLETED");
        order.setTotalAmount(req.finalAmount());
        order.setCompletedAt(now);
        order.setPayoutAt(now.plusDays(3)); 
        order.setDisputed(false);

        orderRepository.save(order);

        // 寫入高權限稽核日誌
        Map<String, Object> payload = Map.of(
            "finalAmount", req.finalAmount(),
            "receiptUrl", req.receiptUrl() != null ? req.receiptUrl() : "",
            "reason", req.reason()
        );
        writeAuditLog(orderId, "ADMIN", "ADMIN_RESOLVED", payload);

        log.info("[CompletionService] Order {} resolved by admin", orderId);
    }

    /**
     * 飼主手動結案入口
     */
    @Transactional
    public void manualComplete(UUID orderId, UUID ownerId) {
        log.info("[CompletionService] Manual completion requested for order: {}", orderId);
        
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("找不到訂單: " + orderId));
        
        if (!order.getOwner().getId().equals(ownerId)) {
            // fix-batch11 I1：越權回 403（過去 IllegalArgumentException → 400）
            throw new org.springframework.security.access.AccessDeniedException("權限不足：您非此訂單的飼主");
        }

        // 狀態白名單（PRD-009 狀態機：服務中的訂單才可結案；fix-batch10 H2）。過去只擋 DISPUTED：
        // - COMPLETED 重複呼叫會重設 completedAt / payoutAt，撥款日期被往後推
        // - MODIFYING / REFUND_VERIFY 等協商中狀態結案會繞過 PRD-016 協商
        String status = order.getStatus();
        if (!MANUAL_COMPLETABLE_STATUSES.contains(status)) {
            throw new IllegalStateException(manualCompleteRejectMessage(status));
        }

        List<Visit> visits = visitRepository.findByOrderId(orderId);
        // 零行程時 allMatch 回 true，會讓「沒有任何服務」的訂單直接結案放款。資料模型不保證至少一筆行程：
        // 預約的 dates 可為空陣列（BookingItemRequest 沒有 @NotEmpty，總額 0 也能通過 Zero-Trust 校驗），
        // 訂單變更確認時 updateVisitsSafely 會刪掉全部未執行行程、dates 為 null/空則不補新行程（驗收 N10）
        if (visits.isEmpty()) {
            throw new IllegalStateException("此訂單沒有任何行程，不可結案");
        }
        boolean allFinished = visits.stream()
                .allMatch(v -> List.of("DONE", "CLOSED_BY_SYSTEM").contains(v.getStatus()));
        
        if (!allFinished) {
            throw new IllegalStateException("尚有未完成的行程，不可結案");
        }

        OffsetDateTime now = OffsetDateTime.now();
        order.setStatus("COMPLETED");
        order.setCompletedAt(now);
        order.setPayoutAt(now.plusDays(3));
        
        orderRepository.save(order);
        log.info("[CompletionService] Order {} manual completed by owner {}", order.getId(), ownerId);
    }

    /** 飼主可手動結案的訂單狀態（與 disputeOrder 一致） */
    static final List<String> MANUAL_COMPLETABLE_STATUSES = List.of("CONFIRMED", "IN_PROGRESS");

    private static String manualCompleteRejectMessage(String status) {
        if ("COMPLETED".equals(status)) {
            return "此訂單已結案，不可重複結案";
        }
        if ("DISPUTED".equals(status)) {
            return "爭議中的訂單不可結案";
        }
        if ("MODIFYING".equals(status) || "REFUND_VERIFY".equals(status)) {
            return "訂單變更協商中，請待協商完成後再結案";
        }
        return "此訂單狀態（" + status + "）不可結案";
    }

    private void writeAuditLog(UUID orderId, String operator, String action, Map<String, Object> payload) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        orderLogRepository.save(OrderLog.builder()
            .order(order)
            .operatorId(operator)
            .actionType(action)
            .payload(payload)
            .build());
    }

    /**
     * 飼主提出爭議 (SD-009)
     */
    @Transactional
    public void disputeOrder(UUID orderId, UUID ownerId, String category, String description) {
        log.info("[CompletionService] Owner {} disputing order: {}", ownerId, orderId);
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("找不到訂單: " + orderId));
        if (!order.getOwner().getId().equals(ownerId)) {
            // fix-batch11 I1：越權回 403（過去 IllegalArgumentException → 400）
            throw new org.springframework.security.access.AccessDeniedException("權限不足：您非此訂單的飼主");
        }
        if (!List.of("CONFIRMED", "IN_PROGRESS").contains(order.getStatus())) {
            throw new IllegalStateException("此訂單狀態不可申報爭議");
        }
        order.setStatus("DISPUTED");
        order.setDisputed(true);
        orderRepository.saveAndFlush(order);

        writeAuditLog(orderId, ownerId.toString(), "DISPUTE_提出", Map.of(
            "category", category != null ? category : "",
            "description", description != null ? description : ""
        ));
    }
}
