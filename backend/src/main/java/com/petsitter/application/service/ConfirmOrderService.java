package com.petsitter.application.service;

import com.petsitter.application.exception.CapacityFullException;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.OrderSnapshot;
import com.petsitter.domain.model.ServicePlan;
import com.petsitter.domain.model.Visit;
import com.petsitter.domain.repository.OrderRepository;
import com.petsitter.domain.repository.OrderSnapshotRepository;
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
    private final OrderSnapshotRepository orderSnapshotRepository;

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

        // 4. 獲取方案與配額檢核 (多方案訂單以下單時的第一個方案為準，沿用 BookingService.createBooking 的既有慣例)
        ServicePlan plan = servicePlanRepository.findById(order.getPlanId())
                .orElseThrow(() -> new IllegalStateException("找不到訂單對應的服務方案"));

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

        // 6. 原價快速接單也要建立方案快照，否則保母打卡後上傳照片/影片會因找不到快照而 404 (PRD-008)
        OrderSnapshot snapshot = OrderSnapshot.builder()
                .order(order)
                .snapshotPlanTitle(plan.getName())
                .snapshotUnitPrice(plan.getPrice().intValue())
                .snapshotOriginalTotal(order.getTotalAmount())
                .adjustmentAmount(0)
                .mediaRetentionDays(plan.getMediaRetentionDays())
                .maxPhotos(plan.getMaxPhotos())
                .maxVideos(OrderSnapshot.DEFAULT_MAX_VIDEOS)
                .maxVideoSeconds(plan.getMaxVideoSeconds())
                .snapshotData(order.getItems())
                .build();
        orderSnapshotRepository.save(snapshot);

        log.info("[ConfirmOrderService] Order {} confirmed and moved to PENDING_PAYMENT", orderId);
    }
}
