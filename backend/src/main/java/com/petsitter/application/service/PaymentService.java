package com.petsitter.application.service;

import com.petsitter.application.dto.UpdateSitterPaymentInfoRequest;
import com.petsitter.domain.event.PaymentProofSubmittedEvent;
import com.petsitter.domain.event.PaymentRejectedEvent;
import com.petsitter.domain.event.PaymentVerifiedEvent;
import com.petsitter.domain.model.BankAccountInfo;
import com.petsitter.domain.model.ModificationRequest;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.Profile;
import com.petsitter.domain.repository.ModificationRequestRepository;
import com.petsitter.domain.repository.OrderRepository;
import com.petsitter.domain.repository.ProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final OrderRepository orderRepository;
    private final ProfileRepository profileRepository;
    private final MediaStorageService mediaStorageService;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final SupplementPaymentResolver supplementPaymentResolver;
    private final ModificationRequestRepository modificationRequestRepository;

    /**
     * 更新保母的銀行收款帳戶資訊 (Sitter)
     */
    @Transactional
    public void updateSitterPaymentInfo(UUID sitterId, UpdateSitterPaymentInfoRequest request) {
        log.info("Updating payment info for sitter: {}", sitterId);
        
        Profile profile = profileRepository.findByUserIdAndType(sitterId, "SITTER")
                .orElseGet(() -> Profile.builder()
                        .userId(sitterId)
                        .type("SITTER")
                        .trustScore(100)
                        .kycStatus("UNVERIFIED")
                        .build());

        BankAccountInfo bankAccountInfo = BankAccountInfo.builder()
                .bankCode(request.bankCode())
                .bankBranch(request.bankBranch())
                .bankAccount(request.bankAccount())
                .bankPayeeName(request.bankPayeeName())
                .build();

        profile.setBankAccountInfo(bankAccountInfo);
        profileRepository.save(profile);
        log.info("Successfully updated payment info for sitter: {}", sitterId);
    }

    /**
     * 飼主上傳付款憑證 (Owner)
     */
    @Transactional
    public void submitPaymentProof(UUID ownerId, UUID orderId, String lastFive, MultipartFile file, boolean disclaimerAgreed, String idempotencyKey) {
        log.info("Submitting payment proof for order: {}, owner: {}, idempotencyKey: {}", orderId, ownerId, idempotencyKey);

        // 1. 查詢訂單
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("找不到對應的訂單: " + orderId));

        // 2. BOLA 安全性檢核
        if (!order.getOwner().getId().equals(ownerId)) {
            log.error("BOLA Violation: Owner {} tried to upload proof for order {} owned by {}", ownerId, orderId, order.getOwner().getId());
            throw new org.springframework.security.access.AccessDeniedException("權限不足：您非此訂單的擁有者");
        }

        // 3. 狀態檢核
        if (!"PENDING_PAYMENT".equals(order.getStatus())) {
            throw new IllegalStateException("訂單目前狀態為 " + order.getStatus() + "，非待付款狀態，無法上傳憑證");
        }

        // 4. 參數防呆
        if (!disclaimerAgreed) {
            throw new IllegalArgumentException("必須勾選並同意線下交易免責聲明");
        }

        if (lastFive == null || !lastFive.matches("^\\d{5}$")) {
            throw new IllegalArgumentException("轉帳帳號後五碼必須為 5 位數字");
        }

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("轉帳憑證圖片檔案不可為空");
        }

        // 檔案限制：大小 <= 5MB
        if (file.getSize() > 5 * 1024 * 1024) {
            throw new IllegalArgumentException("上傳的檔案大小不得超過 5MB");
        }

        // 檔案格式限制：jpg/png/webp
        String contentType = file.getContentType();
        if (contentType == null || (!contentType.equals("image/jpeg") && !contentType.equals("image/png") && !contentType.equals("image/webp"))) {
            throw new IllegalArgumentException("檔案格式不符，僅支援 JPG、PNG、WEBP 圖片");
        }

        // 補款 (PRD-016 C.3)：憑證寫在變更請求上，不覆寫首次付款的憑證/後五碼/冪等鍵/免責同意時間
        Optional<ModificationRequest> supplement = supplementPaymentResolver.findOpenSupplement(order);

        // 5. 冪等性防護：先主動檢驗 DB 是否已存在該 key，避免 GCS 孤兒檔案產生。
        // 首次付款與補款的 key 共用同一個命名空間，兩邊都要查
        if (idempotencyKey != null) {
            if (orderRepository.existsByPaymentIdempotencyKey(idempotencyKey)
                    || modificationRequestRepository.existsBySupplementIdempotencyKey(idempotencyKey)) {
                throw new org.springframework.dao.DataIntegrityViolationException("idx_orders_payment_idempotency duplicate");
            }
        }

        // 6. 上傳至媒體儲存庫（私有物件，回傳 Object Key；檢視時另行簽名，SD-007 §4.7）
        String proofObjectKey = mediaStorageService.uploadPaymentProof(ownerId, orderId, file);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // 7. 更新欄位與狀態為 PAID
        order.setStatus("PAID");
        if (supplement.isPresent()) {
            ModificationRequest mod = supplement.get();
            mod.setSupplementProofKey(proofObjectKey);
            mod.setSupplementProofLastFive(lastFive);
            mod.setSupplementIdempotencyKey(idempotencyKey);
            mod.setSupplementSubmittedAt(now);
            modificationRequestRepository.save(mod);
        } else {
            order.setPaymentIdempotencyKey(idempotencyKey);
            order.setPaymentProofUrl(proofObjectKey);
            order.setPaymentProofLastFive(lastFive);
            order.setDisclaimerAgreed(true);
            order.setDisclaimerAgreedAt(now);
        }

        orderRepository.save(order);

        // 8. 寫入審計日誌
        auditLogService.writeOrderLog(order, ownerId.toString(), "PAYMENT_PROOF_SUBMITTED", Map.of(
                "lastFive", lastFive,
                "objectKey", proofObjectKey,
                "kind", supplement.isPresent() ? "SUPPLEMENT" : "INITIAL",
                "modificationRequestId", supplement.map(m -> m.getId().toString()).orElse("")
        ));

        // 9. 發布事件 (監聽器非同步處理通知發送)
        eventPublisher.publishEvent(new PaymentProofSubmittedEvent(orderId, ownerId, proofObjectKey));
        log.info("Successfully submitted payment proof for order: {}", orderId);
    }

    /**
     * 保母確認入帳 (Sitter)
     */
    @Transactional
    public void verifyPayment(UUID sitterId, UUID orderId) {
        log.info("Verifying payment for order: {}, sitter: {}", orderId, sitterId);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("找不到對應的訂單: " + orderId));

        // BOLA 安全性檢核
        if (!order.getSitter().getId().equals(sitterId)) {
            log.error("BOLA Violation: Sitter {} tried to verify payment for order {} belonging to sitter {}", sitterId, orderId, order.getSitter().getId());
            throw new org.springframework.security.access.AccessDeniedException("權限不足：您非此訂單的被預約保母");
        }

        // 狀態檢核
        if (!"PAID".equals(order.getStatus())) {
            throw new IllegalStateException("訂單狀態非待核對付款狀態");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Optional<ModificationRequest> supplement = supplementPaymentResolver.findOpenSupplement(order);
        String restoredStatus;
        if (supplement.isPresent()) {
            // 補款核帳 (PRD-016 C.3)：訂單回到發起變更前的狀態（CONFIRMED 或 IN_PROGRESS），
            // 與拒絕變更 (B.3) 的復原邏輯一致；過去一律寫死 CONFIRMED，進行中的訂單會被退回「未開始」。
            // paidAt 是首次付款的核帳時間，補款不覆寫，補款核帳時間記在變更請求上
            ModificationRequest mod = supplement.get();
            restoredStatus = mod.getPreviousStatus() != null ? mod.getPreviousStatus() : "CONFIRMED";
            mod.setSupplementVerifiedAt(now);
            modificationRequestRepository.save(mod);
        } else {
            restoredStatus = "CONFIRMED";
            order.setPaidAt(now);
        }
        order.setStatus(restoredStatus);
        orderRepository.save(order);

        // 寫入審計日誌
        auditLogService.writeOrderLog(order, sitterId.toString(), "PAYMENT_VERIFIED", Map.of(
                "verifiedAt", now.toString(),
                "kind", supplement.isPresent() ? "SUPPLEMENT" : "INITIAL",
                "restoredStatus", restoredStatus
        ));

        // 發布事件
        eventPublisher.publishEvent(new PaymentVerifiedEvent(orderId, sitterId));
        log.info("Successfully verified payment for order: {}", orderId);
    }

    /**
     * 保母駁回憑證 (Sitter)
     */
    @Transactional
    public void rejectPayment(UUID sitterId, UUID orderId, String rejectReason) {
        log.info("Rejecting payment for order: {}, sitter: {}, reason: {}", orderId, sitterId, rejectReason);

        if (rejectReason == null || rejectReason.isBlank()) {
            throw new IllegalArgumentException("退回原因不得為空");
        }
        if (rejectReason.length() > 500) {
            throw new IllegalArgumentException("退回原因長度上限為 500 字元");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("找不到對應的訂單: " + orderId));

        // BOLA 安全性檢核
        if (!order.getSitter().getId().equals(sitterId)) {
            log.error("BOLA Violation: Sitter {} tried to reject payment for order {} belonging to sitter {}", sitterId, orderId, order.getSitter().getId());
            throw new org.springframework.security.access.AccessDeniedException("權限不足：您非此訂單的被預約保母");
        }

        // 狀態檢核
        if (!"PAID".equals(order.getStatus())) {
            throw new IllegalStateException("訂單狀態非待核對付款狀態");
        }

        // 退回狀態並重置欄位
        Optional<ModificationRequest> supplement = supplementPaymentResolver.findOpenSupplement(order);
        order.setStatus("PENDING_PAYMENT");
        if (supplement.isPresent()) {
            // 駁回的是補款憑證：只清補款欄位，首次付款的憑證與免責同意紀錄保留
            ModificationRequest mod = supplement.get();
            mod.setSupplementProofKey(null);
            mod.setSupplementProofLastFive(null);
            mod.setSupplementIdempotencyKey(null); // 容許帶新 key 重新提交
            mod.setSupplementSubmittedAt(null);
            modificationRequestRepository.save(mod);
        } else {
            order.setPaymentProofUrl(null);
            order.setPaymentProofLastFive(null);
            order.setPaymentIdempotencyKey(null); // 重置為 null 容許重新提交
            order.setDisclaimerAgreed(false);
            order.setDisclaimerAgreedAt(null);
        }

        orderRepository.save(order);

        // 寫入審計日誌
        auditLogService.writeOrderLog(order, sitterId.toString(), "PAYMENT_REJECTED", Map.of(
                "rejectReason", rejectReason,
                "kind", supplement.isPresent() ? "SUPPLEMENT" : "INITIAL"
        ));

        // 發布事件
        eventPublisher.publishEvent(new PaymentRejectedEvent(orderId, sitterId, rejectReason));
        log.info("Successfully rejected payment for order: {}", orderId);
    }

    /**
     * 保母取得個人轉帳帳戶資訊
     */
    @Transactional(readOnly = true)
    public BankAccountInfo getSitterPaymentInfo(UUID sitterId) {
        Profile profile = profileRepository.findByUserIdAndType(sitterId, "SITTER").orElse(null);
        BankAccountInfo bankAccountInfo = null;
        if (profile != null) {
            bankAccountInfo = profile.getBankAccountInfo();
        }
        if (bankAccountInfo == null) {
            bankAccountInfo = BankAccountInfo.builder().build();
        }
        return bankAccountInfo;
    }
}
