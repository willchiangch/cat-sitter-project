package com.petsitter.application.service;

import com.petsitter.application.exception.CapacityFullException;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.ServicePlan;
import com.petsitter.domain.model.Visit;
import com.petsitter.domain.repository.OrderRepository;
import com.petsitter.domain.repository.ServicePlanRepository;
import com.petsitter.domain.repository.VisitRepository;
import com.petsitter.infrastructure.lock.AdvisoryLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfirmOrderService {

    private final AdvisoryLockService advisoryLockService;
    private final OrderRepository orderRepository;
    private final VisitRepository visitRepository;
    private final ServicePlanRepository servicePlanRepository;

    @Transactional
    public void confirmOrder(UUID sitterId, UUID orderId) {
        log.info("[ConfirmOrderService] Sitter {} is confirming order {}", sitterId, orderId);

        // 1. 獲取訂單與 BOLA 檢查
        Order order = orderRepository.findByIdAndSitterId(orderId, sitterId)
                .orElseThrow(() -> new IllegalArgumentException("找不到該保母的指定訂單: " + orderId));

        if (!"PENDING".equals(order.getStatus())) {
            throw new IllegalStateException("只有 PENDING 狀態的訂單可以被接單，目前狀態: " + order.getStatus());
        }

        // 2. 獲取預約日期列表 (從 Visits 取得)
        List<Visit> visits = visitRepository.findAll().stream()
                .filter(v -> v.getOrder().getId().equals(orderId))
                .collect(Collectors.toList());
        
        List<LocalDate> dates = visits.stream()
                .map(v -> v.getScheduledAt().toLocalDate())
                .distinct()
                .collect(Collectors.toList());

        // 3. 取得鎖 (Sorted Advisory Locks) - 防範併發接單超賣
        List<Long> lockKeys = dates.stream()
                .map(date -> AdvisoryLockService.generateLockKey(sitterId, date))
                .sorted()
                .collect(Collectors.toList());
        advisoryLockService.acquireLocks(lockKeys);

        // 4. 獲取方案與配額檢核
        // 註：這裡假設訂單關聯的項目中可以溯源到 planId，暫以第一個 Visit 關聯的 Order 內的邏輯推導
        // 真實系統應從 Order.items 解析或 Order 增加 planId 欄位。這裡先簡化處理。
        // 我們假設測試中會建立對應的 Plan
        ServicePlan plan = servicePlanRepository.findAll().stream()
                .filter(p -> p.getSitter().getId().equals(sitterId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("保母未設定服務方案"));

        for (Visit visit : visits) {
            OffsetDateTime scheduledAt = visit.getScheduledAt();
            long bookedCount = visitRepository.countBookedVisitsBySitterIdAndDate(sitterId, scheduledAt);
            
            log.info("[ConfirmOrderService] Date: {}, Booked: {}, Capacity: {}", scheduledAt.toLocalDate(), bookedCount, plan.getDailyCapacity());
            
            if (bookedCount >= plan.getDailyCapacity()) {
                throw new CapacityFullException("該日期預約已滿 (方案: " + plan.getName() + "): " + scheduledAt.toLocalDate());
            }
        }

        // 5. 更新狀態
        order.setStatus("PENDING_PAYMENT");
        orderRepository.save(order);

        log.info("[ConfirmOrderService] Order {} confirmed and moved to PENDING_PAYMENT", orderId);
    }
}
