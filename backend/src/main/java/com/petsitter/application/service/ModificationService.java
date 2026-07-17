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
    public void proposeModification(UUID requesterId, UUID orderId, ModificationPayloadDto proposed, String requestedBy) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        boolean callerIsOwner = order.getOwner().getId().equals(requesterId);
        boolean callerIsSitter = order.getSitter().getId().equals(requesterId);
        if (("OWNER".equals(requestedBy) && !callerIsOwner) || ("SITTER".equals(requestedBy) && !callerIsSitter)
                || (!callerIsOwner && !callerIsSitter)) {
            throw new IllegalArgumentException("無權操作此訂單");
        }

        if (!List.of("CONFIRMED", "IN_PROGRESS").contains(order.getStatus())) {
            throw new IllegalStateException("訂單當前狀態不允許變更");
        }

        OrderSnapshot snapshot = snapshotRepo.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("找不到訂單快照，無法計算變更金額"));
        
        int originalUnitPrice = snapshot.getSnapshotUnitPrice();
        int newCalculatedTotal = proposed.getTotalDays() * originalUnitPrice;
        int diffAmount = newCalculatedTotal - order.getTotalAmount();

        List<String> newDates = proposed.getDates() == null ? List.of()
                : proposed.getDates().stream().map(LocalDate::toString).toList();

        ModificationRequest modReq = ModificationRequest.builder()
                .order(order)
                .requestedBy(requestedBy)
                .payload(proposed.getItems())
                .dates(newDates)
                .diffAmount(diffAmount)
                .newTotalAmount(newCalculatedTotal)
                .previousStatus(order.getStatus())
                .status("PENDING_REVIEW")
                .build();

        modRepo.save(modReq);

        order.setStatus("MODIFYING");
        orderRepository.save(order);
    }

    /**
     * 保母審核變更並提供差額報價 (PRD-016 主流程 B)
     */
    @Transactional
    public void quoteModification(UUID sitterId, UUID orderId, UUID modRequestId, int newTotalAmount, int version) {
        ModificationRequest modReq = modRepo.findById(modRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Modification request not found"));
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        if (!order.getSitter().getId().equals(sitterId)) {
            throw new IllegalArgumentException("無權操作此訂單");
        }
        if (!"PENDING_REVIEW".equals(modReq.getStatus())) {
            throw new IllegalStateException("此變更請求已審核或已失效");
        }
        if (!order.getVersion().equals(version)) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(Order.class, orderId);
        }
        if (newTotalAmount < 0) {
            throw new IllegalArgumentException("報價金額不可為負數");
        }

        modReq.setNewTotalAmount(newTotalAmount);
        modReq.setDiffAmount(newTotalAmount - order.getTotalAmount());
        modReq.setStatus("QUOTED");
        modRepo.save(modReq);
    }

    /**
     * 保母拒絕變更請求，訂單恢復原狀態 (PRD-016 主流程 B.3)
     */
    @Transactional
    public void rejectModification(UUID sitterId, UUID orderId, UUID modRequestId) {
        ModificationRequest modReq = modRepo.findById(modRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Modification request not found"));
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        if (!order.getSitter().getId().equals(sitterId)) {
            throw new IllegalArgumentException("無權操作此訂單");
        }
        if (!List.of("PENDING_REVIEW", "QUOTED").contains(modReq.getStatus())) {
            throw new IllegalStateException("此變更請求已審核或已失效");
        }

        modReq.setStatus("REJECTED");
        modRepo.save(modReq);

        order.setStatus(modReq.getPreviousStatus() != null ? modReq.getPreviousStatus() : "CONFIRMED");
        orderRepository.save(order);
    }

    @Transactional
    public void confirmModification(UUID ownerId, UUID orderId, UUID modRequestId, int agreedDiffAmount) {
        ModificationRequest modReq = modRepo.findById(modRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Modification request not found"));
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        if (!order.getOwner().getId().equals(ownerId)) {
            throw new IllegalArgumentException("無權操作此訂單");
        }
        if (!"QUOTED".equals(modReq.getStatus())) {
            throw new IllegalStateException("此變更請求尚未經保母報價，無法確認");
        }
        // Zero-Trust: 確認金額須與保母最新報價一致，防止協商期間資料過期 (SD-016 §1.2)
        if (!modReq.getDiffAmount().equals(agreedDiffAmount)) {
            throw new com.petsitter.application.exception.PricingMismatchException("同意金額與保母最新報價不符，請重新整理後再試");
        }

        // 新日期以發起變更當下鎖定的內容為準，不信任前端於確認階段重新提交 (Zero-Trust)
        List<LocalDate> newDates = modReq.getDates() == null ? List.of()
                : modReq.getDates().stream().map(LocalDate::parse).toList();

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

    /**
     * 查詢訂單目前進行中的變更請求 (PRD-016)，供保母報價頁 / 飼主確認頁串接真實資料，取代前端寫死測試 UUID
     */
    @Transactional(readOnly = true)
    public com.petsitter.application.dto.ModificationRequestDetailDto getActiveModificationRequest(UUID requesterId, UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        if (!order.getOwner().getId().equals(requesterId) && !order.getSitter().getId().equals(requesterId)) {
            throw new IllegalArgumentException("無權操作此訂單");
        }

        ModificationRequest modReq = modRepo.findByOrderIdAndStatusIn(orderId, List.of("PENDING_REVIEW", "QUOTED"))
                .orElseThrow(() -> new IllegalStateException("此訂單目前沒有進行中的變更請求"));

        return com.petsitter.application.dto.ModificationRequestDetailDto.builder()
                .id(modReq.getId())
                .orderId(orderId)
                .status(modReq.getStatus())
                .requestedBy(modReq.getRequestedBy())
                .diffAmount(modReq.getDiffAmount())
                .newTotalAmount(modReq.getNewTotalAmount())
                .currentOrderTotalAmount(order.getTotalAmount())
                .orderVersion(order.getVersion())
                .dates(modReq.getDates())
                .refundProofUrl(modReq.getRefundProofUrl())
                .build();
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