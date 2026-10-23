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

    private final OrderRepository orderRepository;
    private final MediaStorageService mediaStorageService;

    public record SignedProofUrl(String signedUrl, long expiresInSeconds) {
    }

    @Transactional(readOnly = true)
    public SignedProofUrl generateSignedUrl(UUID requesterId, UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("找不到對應的訂單: " + orderId));

        boolean isOwner = order.getOwner().getId().equals(requesterId);
        boolean isSitter = order.getSitter().getId().equals(requesterId);
        if (!isOwner && !isSitter) {
            log.error("BOLA Violation: User {} tried to read payment proof of order {}", requesterId, orderId);
            throw new AccessDeniedException("權限不足：您非此訂單的飼主或服務保母");
        }

        String storedRef = order.getPaymentProofUrl();
        if (storedRef == null || storedRef.isBlank()) {
            throw new IllegalStateException("此訂單目前沒有付款憑證");
        }
        return sign(storedRef);
    }

    private SignedProofUrl sign(String storedRef) {
        String objectKey = mediaStorageService.resolveObjectKey(storedRef);
        // 只簽 payment-proofs/ 底下的物件：DB 若混入其他網址（舊測試資料、外部連結），
        // 不能讓這支端點變成任意物件的簽名產生器
        if (objectKey == null || !objectKey.startsWith(PAYMENT_PROOF_PREFIX)) {
            throw new IllegalStateException("付款憑證儲存位置異常，無法產生檢視網址");
        }
        String signedUrl = mediaStorageService.generateSignedUrl(objectKey, SIGNED_URL_TTL);
        return new SignedProofUrl(signedUrl, SIGNED_URL_TTL.toSeconds());
    }
}
