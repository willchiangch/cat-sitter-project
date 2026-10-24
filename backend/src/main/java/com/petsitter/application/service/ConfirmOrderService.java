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

    private final OrderRepository orderRepository;
    private final ServicePlanRepository servicePlanRepository;
    private final OrderSnapshotRepository orderSnapshotRepository;

    @Transactional
    public void confirmOrder(UUID sitterId, UUID orderId) {
        log.info("[ConfirmOrderService] Sitter {} is confirming order {}", sitterId, orderId);

        // 1. 獲取訂單與 BOLA 檢查；鎖住訂單列，同一張單併發接單 / 報價時後到者讀到最新狀態 → 409
        Order order = orderRepository.findByIdAndSitterIdForUpdate(orderId, sitterId)
                .orElseThrow(() -> new IllegalArgumentException("找不到該保母的指定訂單: " + orderId));

        if (!"PENDING".equals(order.getStatus())) {
            throw new IllegalStateException("只有 PENDING 狀態的訂單可以被接單，目前狀態: " + order.getStatus());
        }

        // 2. 方案 (多方案訂單以下單時的第一個方案為準，沿用 BookingService.createBooking 的既有慣例)
        ServicePlan plan = servicePlanRepository.findById(order.getPlanId())
                .orElseThrow(() -> new IllegalStateException("找不到訂單對應的服務方案"));

        // 3. 更新狀態（系統不做每日容量控管：接不接單由保母自行判斷，PRD-005 / PRD-006）
        order.setStatus("PENDING_PAYMENT");
        orderRepository.save(order);

        // 4. 原價快速接單也要建立方案快照，否則保母打卡後上傳照片/影片會因找不到快照而 404 (PRD-008)
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
