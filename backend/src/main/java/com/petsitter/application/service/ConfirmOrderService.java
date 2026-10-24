package com.petsitter.application.service;

import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.OrderSnapshot;
import com.petsitter.domain.model.ServicePlan;
import com.petsitter.domain.repository.OrderRepository;
import com.petsitter.domain.repository.OrderSnapshotRepository;
import com.petsitter.domain.repository.ServicePlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfirmOrderService {

    private final CapacityGuard capacityGuard;
    private final OrderRepository orderRepository;
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

        // 2. 方案 (多方案訂單以下單時的第一個方案為準，沿用 BookingService.createBooking 的既有慣例)
        ServicePlan plan = servicePlanRepository.findById(order.getPlanId())
                .orElseThrow(() -> new IllegalStateException("找不到訂單對應的服務方案"));

        // 3~4. Sorted Advisory Locks + 鎖內容量檢核，與議價報價 EvaluationService.sendQuote 共用 (backlog G1)
        capacityGuard.lockAndCheck(sitterId, orderId, plan);

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
