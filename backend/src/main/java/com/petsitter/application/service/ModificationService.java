package com.petsitter.application.service;

import com.petsitter.application.dto.ModificationPayloadDto;
import com.petsitter.domain.model.*;
import com.petsitter.domain.repository.*;
import com.petsitter.infrastructure.lock.AdvisoryLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModificationService {

    private final OrderRepository orderRepository;
    private final ModificationRequestRepository modRepo;
    private final OrderSnapshotRepository snapshotRepo;
    private final VisitRepository visitRepository;
    private final ServicePlanRepository servicePlanRepository;
    private final AdvisoryLockService lockService;

    @Transactional
    public void proposeModification(UUID orderId, ModificationPayloadDto proposed, String requestedBy) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        if (!List.of("CONFIRMED", "IN_PROGRESS").contains(order.getStatus())) {
            throw new IllegalStateException("訂單當前狀態不允許變更");
        }

        OrderSnapshot snapshot = snapshotRepo.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("找不到訂單快照，無法計算變更金額"));
        
        int originalUnitPrice = snapshot.getSnapshotUnitPrice();
        int newCalculatedTotal = proposed.getTotalDays() * originalUnitPrice;
        int diffAmount = newCalculatedTotal - order.getTotalAmount();

        ModificationRequest modReq = ModificationRequest.builder()
                .order(order)
                .requestedBy(requestedBy)
                .payload(proposed.getItems())
                .diffAmount(diffAmount)
                .status("PENDING_REVIEW")
                .build();
        
        modRepo.save(modReq);

        order.setStatus("MODIFYING");
        orderRepository.save(order);
    }

    @Transactional
    public void confirmModification(UUID orderId, UUID modRequestId, List<LocalDate> newDates) {
        ModificationRequest modReq = modRepo.findById(modRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Modification request not found"));
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        if (!"PENDING_REVIEW".equals(modReq.getStatus())) {
            throw new IllegalStateException("Modification request is not in PENDING_REVIEW state");
        }

        List<LocalDate> currentDates = visitRepository.findByOrderId(orderId).stream()
                .map(v -> v.getScheduledAt().toLocalDate())
                .collect(Collectors.toList());
        
        Set<LocalDate> allInvolvedDates = new HashSet<>(currentDates);
        allInvolvedDates.addAll(newDates);
        
        List<Long> lockKeys = allInvolvedDates.stream()
                .map(date -> AdvisoryLockService.generateLockKey(order.getSitter().getId(), date))
                .sorted()
                .distinct()
                .toList();
        
        lockService.acquireLocks(lockKeys);

        ServicePlan plan = servicePlanRepository.findById(order.getPlanId()).orElseThrow();
        for (LocalDate date : newDates) {
            OffsetDateTime start = date.atStartOfDay().atOffset(ZoneOffset.UTC);
            OffsetDateTime end = date.atTime(23, 59, 59).atOffset(ZoneOffset.UTC);
            int occupied = visitRepository.countOccupiedCapacityWithSelfExclusion(
                    order.getSitter().getId(), start, end, order.getId());
            
            if (occupied >= plan.getDailyCapacity()) {
                throw new IllegalStateException("日期 " + date + " 名額已滿");
            }
        }

        order.setItems(modReq.getPayload());
        order.setTotalAmount(order.getTotalAmount() + modReq.getDiffAmount());
        
        updateVisitsSafely(order, newDates);

        if (modReq.getDiffAmount() > 0) {
            order.setStatus("PENDING_PAYMENT");
        } else if (modReq.getDiffAmount() < 0) {
            order.setStatus("REFUND_VERIFY");
        } else {
            order.setStatus("CONFIRMED");
        }

        modReq.setStatus("M_DONE");
        orderRepository.save(order);
        modRepo.save(modReq);
    }

    private void updateVisitsSafely(Order order, List<LocalDate> newDates) {
        List<Visit> existingVisits = visitRepository.findByOrderId(order.getId());
        
        List<Visit> doneVisits = existingVisits.stream()
                .filter(v -> "DONE".equals(v.getStatus()))
                .collect(Collectors.toList());
        
        List<Visit> pendingVisits = existingVisits.stream()
                .filter(v -> "PENDING".equals(v.getStatus()) || "CONFIRMED".equals(v.getStatus()))
                .collect(Collectors.toList());

        visitRepository.deleteAll(pendingVisits);

        Set<LocalDate> doneDates = doneVisits.stream()
                .map(v -> v.getScheduledAt().toLocalDate())
                .collect(Collectors.toSet());

        List<Visit> newVisits = newDates.stream()
                .filter(date -> !doneDates.contains(date))
                .map(date -> Visit.builder()
                        .order(order)
                        .status("PENDING")
                        .scheduledAt(date.atStartOfDay().atOffset(ZoneOffset.UTC))
                        .build())
                .collect(Collectors.toList());

        visitRepository.saveAll(newVisits);
    }

    @Transactional
    public void uploadRefundProof(UUID orderId, UUID sitterId, String refundProofUrl) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("找不到訂單: " + orderId));
        if (!order.getSitter().getId().equals(sitterId)) {
            throw new IllegalArgumentException("無權操作此訂單");
        }
        ModificationRequest modReq = modRepo.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("找不到變更請求記錄"));
        modReq.setRefundProofUrl(refundProofUrl);
        modRepo.saveAndFlush(modReq);
    }

    @Transactional
    public void confirmRefund(UUID orderId, UUID ownerId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("找不到訂單: " + orderId));
        if (!order.getOwner().getId().equals(ownerId)) {
            throw new IllegalArgumentException("無權操作此訂單");
        }
        if (!"REFUND_VERIFY".equals(order.getStatus())) {
            throw new IllegalStateException("訂單當前狀態不符合退款確認條件");
        }

        // 若無預約項目，表示為整筆取消
        if (order.getItems() == null || order.getItems().isEmpty()) {
            order.setStatus("CANCELLED");
        } else {
            order.setStatus("CONFIRMED");
        }
        orderRepository.saveAndFlush(order);
    }
}