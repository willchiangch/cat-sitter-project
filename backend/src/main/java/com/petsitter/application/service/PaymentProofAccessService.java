package com.petsitter.application.service;

import com.petsitter.domain.model.Order;
import com.petsitter.domain.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

/**
 * 付款憑證檢視 (SD-007 §4.7)：憑證是私有 GCS 物件，只有訂單的飼主與保母能換到短效簽名網址。
 * 比照 KYC 證件照 (SD-017 §2.3) 的作法，TTL 同為 10 分鐘。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentProofAccessService {

    public static final Duration SIGNED_URL_TTL = Duration.ofMinutes(10);
    static final String PAYMENT_PROOF_PREFIX = "payment-proofs/";
    static final String REFUND_PROOF_PREFIX = "refund-proofs/";

    private final OrderRepository orderRepository;
    private final MediaStorageService mediaStorageService;
    private final SupplementPaymentResolver supplementPaymentResolver;
    private final com.petsitter.domain.repository.ModificationRequestRepository modificationRequestRepository;

    public record SignedProofUrl(String signedUrl, long expiresInSeconds) {
    }

    @Transactional(readOnly = true)
    public SignedProofUrl generateSignedUrl(UUID requesterId, UUID orderId) {
        Order order = loadOrderForParty(requesterId, orderId, "payment proof");

        // 補款階段簽補款憑證，其餘簽首次付款憑證 (SD-016 §4.2)
        String storedRef = supplementPaymentResolver.currentProof(order).storedRef();
        if (storedRef == null || storedRef.isBlank()) {
            throw new IllegalStateException("此訂單目前沒有付款憑證");
        }
        return sign(storedRef, PAYMENT_PROOF_PREFIX, "付款憑證");
    }

    /**
     * 退款憑證 (SD-016 §2.5)：簽該訂單最新一筆變更請求的退款憑證，權限同付款憑證（僅飼主/保母）。
     * 2026-10 前保母手填的外部網址不會被簽、也不會回傳給前端（409），飼主端不可能點到任意外部連結。
     */
    @Transactional(readOnly = true)
    public SignedProofUrl generateRefundProofSignedUrl(UUID requesterId, UUID orderId) {
        loadOrderForParty(requesterId, orderId, "refund proof");
        String storedRef = modificationRequestRepository.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .findFirst()
                .map(com.petsitter.domain.model.ModificationRequest::getRefundProofUrl)
                .orElse(null);
        if (storedRef == null || storedRef.isBlank()) {
            throw new IllegalStateException("此訂單目前沒有退款憑證");
        }
        return sign(storedRef, REFUND_PROOF_PREFIX, "退款憑證");
    }

    private Order loadOrderForParty(UUID requesterId, UUID orderId, String what) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("找不到對應的訂單: " + orderId));

        boolean isOwner = order.getOwner().getId().equals(requesterId);
        boolean isSitter = order.getSitter().getId().equals(requesterId);
        if (!isOwner && !isSitter) {
            log.error("BOLA Violation: User {} tried to read {} of order {}", requesterId, what, orderId);
            throw new AccessDeniedException("權限不足：您非此訂單的飼主或服務保母");
        }
        return order;
    }

    private SignedProofUrl sign(String storedRef, String requiredPrefix, String label) {
        String objectKey = mediaStorageService.resolveObjectKey(storedRef);
        // 只簽指定前綴底下的物件：DB 若混入其他網址（舊測試資料、外部連結），
        // 不能讓這支端點變成任意物件的簽名產生器
        if (objectKey == null || !objectKey.startsWith(requiredPrefix)) {
            throw new IllegalStateException(label + "儲存位置異常，無法產生檢視網址");
        }
        String signedUrl = mediaStorageService.generateSignedUrl(objectKey, SIGNED_URL_TTL);
        return new SignedProofUrl(signedUrl, SIGNED_URL_TTL.toSeconds());
    }
}
