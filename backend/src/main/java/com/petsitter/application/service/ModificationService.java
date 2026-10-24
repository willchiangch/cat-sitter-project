package com.petsitter.application.service;

import com.petsitter.application.dto.ModificationPayloadDto;
import com.petsitter.domain.model.*;
import com.petsitter.domain.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
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
    private final MediaStorageService mediaStorageService;

    /** 退款憑證檔案限制，與付款憑證 (SD-007 §5.1) 相同 */
    static final long MAX_PROOF_BYTES = 5L * 1024 * 1024;
    static final Set<String> ALLOWED_PROOF_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

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

        order.setStatus(restoreStatus(modReq));
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

        // 系統不做每日容量控管（PRD-005 / PRD-006：接不接單由保母自行判斷），變更日期不檢查名額；
        // 同一張訂單的併發確認由 orders.version 樂觀鎖擋下（後到者 409）

        order.setItems(modReq.getPayload());
        order.setTotalAmount(order.getTotalAmount() + modReq.getDiffAmount());
        
        updateVisitsSafely(order, newDates);

        if (modReq.getDiffAmount() > 0) {
            order.setStatus("PENDING_PAYMENT");
        } else if (modReq.getDiffAmount() < 0) {
            order.setStatus("REFUND_VERIFY");
        } else if (isWholeCancellation(order)) {
            // 整筆取消且無差額（例如保母同意取消但不退款）：PRD-016 C.3 / AC-4 → CANCELLED，
            // 與 confirmRefund() 的整筆取消判斷共用 isWholeCancellation()。過去直接走下面的復原分支，
            // 沒有任何預約項目的訂單會回到 CONFIRMED/IN_PROGRESS
            order.setStatus("CANCELLED");
        } else {
            // 無差額 (PRD-016 C.3「直接恢復」)：回到發起變更前的狀態，與拒絕變更/補款核帳一致；
            // 過去寫死 CONFIRMED，進行中 (IN_PROGRESS) 的訂單會被退回「已確認未開始」
            order.setStatus(restoreStatus(modReq));
        }

        modReq.setStatus("M_DONE");
        orderRepository.save(order);
        modRepo.save(modReq);
    }

    /**
     * 查詢訂單目前進行中的變更請求 (PRD-016)，供保母報價頁 / 飼主確認頁串接真實資料，取代前端寫死測試 UUID。
     * 沒有進行中的協商（從未發起、已結清、已拒絕）是正常狀態，回 Optional.empty()，由 controller 回 204；
     * 2026-10 前丟 IllegalStateException → 409，前端只能把正常終態當成錯誤紅字顯示（驗收 N8）。
     */
    @Transactional(readOnly = true)
    public java.util.Optional<com.petsitter.application.dto.ModificationRequestDetailDto> getActiveModificationRequest(UUID requesterId, UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        if (!order.getOwner().getId().equals(requesterId) && !order.getSitter().getId().equals(requesterId)) {
            throw new IllegalArgumentException("無權操作此訂單");
        }

        // confirmModification() 一旦成功就把 modReq.status 設成終態 M_DONE，訂單則依差額轉
        // PENDING_PAYMENT/REFUND_VERIFY。若只認 PENDING_REVIEW/QUOTED 兩種狀態，飼主一確認，
        // 保母端 SitterModificationQuote 頁面（含退款憑證上傳表單）只要重新整理就會 404，
        // 退款流程恰好就是「飼主先確認、保母才需要上傳憑證」，導致保母事實上永遠上傳不了退款
        // 憑證。訂單處於這三個「協商相關」狀態時，改直接撈該訂單最新一筆變更請求（不論其
        // status），讓已確認但退補款尚未走完的流程依然讀得到資料。
        List<String> negotiationRelatedStatuses = List.of("MODIFYING", "PENDING_PAYMENT", "REFUND_VERIFY");
        ModificationRequest modReq;
        if (negotiationRelatedStatuses.contains(order.getStatus())) {
            List<ModificationRequest> history = modRepo.findByOrderIdOrderByCreatedAtDesc(orderId);
            modReq = history.isEmpty() ? null : history.get(0);
        } else {
            modReq = modRepo.findByOrderIdAndStatusIn(orderId, List.of("PENDING_REVIEW", "QUOTED")).orElse(null);
        }
        if (modReq == null) {
            return java.util.Optional.empty();
        }

        return java.util.Optional.of(com.petsitter.application.dto.ModificationRequestDetailDto.builder()
                .id(modReq.getId())
                .orderId(orderId)
                .status(modReq.getStatus())
                .orderStatus(order.getStatus())
                .requestedBy(modReq.getRequestedBy())
                .diffAmount(modReq.getDiffAmount())
                .newTotalAmount(modReq.getNewTotalAmount())
                .currentOrderTotalAmount(order.getTotalAmount())
                .orderVersion(order.getVersion())
                .dates(modReq.getDates())
                .hasRefundProof(modReq.getRefundProofUrl() != null && !modReq.getRefundProofUrl().isBlank())
                .build());
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
    public void uploadRefundProof(UUID orderId, UUID sitterId, org.springframework.web.multipart.MultipartFile file) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("找不到訂單: " + orderId));
        if (!order.getSitter().getId().equals(sitterId)) {
            throw new IllegalArgumentException("無權操作此訂單");
        }
        // 只有「飼主已同意減量變更、等待退款」這一個階段能上傳退款憑證。過去沒有任何狀態防護，
        // 協商中、補款中、甚至飼主已確認收到退款之後都還能改寫最新一筆變更請求的憑證 (SD-016 §2.5)
        if (!"REFUND_VERIFY".equals(order.getStatus())) {
            throw new IllegalStateException("訂單目前不在待退款確認狀態，無法上傳退款憑證");
        }
        ModificationRequest modReq = modRepo.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("找不到變更請求記錄"));
        if (!"M_DONE".equals(modReq.getStatus()) || modReq.getDiffAmount() == null || modReq.getDiffAmount() >= 0) {
            throw new IllegalStateException("此變更請求不涉及退款，無法上傳退款憑證");
        }
        // 退款憑證改為上傳圖片、存私有 Object Key (SD-016 §2.5)。過去收保母手填的任意網址字串，
        // 飼主端點開等於點一個保母給的外部連結（釣魚/追蹤）。狀態與檔案都檢查完才上傳，避免 GCS 孤兒檔
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("退款憑證圖片檔案不可為空");
        }
        if (file.getSize() > MAX_PROOF_BYTES) {
            throw new IllegalArgumentException("上傳的檔案大小不得超過 5MB");
        }
        if (file.getContentType() == null || !ALLOWED_PROOF_TYPES.contains(file.getContentType())) {
            throw new IllegalArgumentException("檔案格式不符，僅支援 JPG、PNG、WEBP 圖片");
        }
        String objectKey = mediaStorageService.uploadRefundProof(sitterId, orderId, file);
        // 欄位名稱沿用 refund_proof_url（不做 migration），2026-10 起存的是 Object Key
        modReq.setRefundProofUrl(objectKey);
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

        if (isWholeCancellation(order)) {
            order.setStatus("CANCELLED");
        } else {
            // PRD-016 C.3 退款分支：「恢復 CONFIRMED 或 IN_PROGRESS」，依發起變更當下的快照復原。
            // 過去寫死 CONFIRMED，進行中的訂單退款後會被退回「已確認未開始」
            ModificationRequest latest = modRepo.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                    .findFirst()
                    .orElse(null);
            order.setStatus(restoreStatus(latest));
        }
        orderRepository.saveAndFlush(order);
    }

    /**
     * 整筆取消（PRD-016 A.2）：變更後已無任何預約項目。前端「申請完全取消」送出空的 items/dates，
     * confirmModification() 會把 payload 寫回 Order.items，因此結清時以訂單上的 items 判斷。
     * 退款確認與無差額結清共用，避免兩條路徑對「整筆取消」各自判斷。
     */
    static boolean isWholeCancellation(Order order) {
        return order.getItems() == null || order.getItems().isEmpty();
    }

    /**
     * 變更結清後訂單要回到的狀態：發起變更當下快照的 previousStatus（CONFIRMED / IN_PROGRESS），
     * 舊資料沒有快照時 fallback CONFIRMED。拒絕變更、補款核帳 (PaymentService) 同一套規則。
     */
    static String restoreStatus(ModificationRequest modReq) {
        if (modReq == null || modReq.getPreviousStatus() == null) {
            return "CONFIRMED";
        }
        return modReq.getPreviousStatus();
    }
}