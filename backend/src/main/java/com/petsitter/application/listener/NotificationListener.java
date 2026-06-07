package com.petsitter.application.listener;

import com.petsitter.application.event.VisitNotificationEvent;
import com.petsitter.application.service.NotificationService;
import com.petsitter.domain.event.PaymentProofSubmittedEvent;
import com.petsitter.domain.event.PaymentRejectedEvent;
import com.petsitter.domain.event.PaymentVerifiedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationListener {

    private final NotificationService notificationService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentProofSubmitted(PaymentProofSubmittedEvent event) {
        log.info("Async notification - Payment Proof Submitted for Order: {}, Owner: {}, URL: {}", 
                event.orderId(), event.ownerId(), event.paymentProofUrl());
        // 本地開發與 E2E 測試時僅輸出日誌，實現非同步解耦
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentVerified(PaymentVerifiedEvent event) {
        log.info("Async notification - Payment Verified for Order: {}, Sitter: {}", 
                event.orderId(), event.sitterId());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentRejected(PaymentRejectedEvent event) {
        log.info("Async notification - Payment Rejected for Order: {}, Sitter: {}, Reason: {}", 
                event.orderId(), event.sitterId(), event.rejectReason());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleVisitNotification(VisitNotificationEvent event) {
        notificationService.sendNotification(event.userId(), event.message());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onKycReviewed(com.petsitter.domain.event.KycReviewedEvent event) {
        log.info("Async notification - KYC Reviewed for record: {}, Sitter: {}, status: {}, reason: {}", 
                event.recordId(), event.sitterId(), event.status(), event.rejectReason());
        String message = "APPROVED".equals(event.status()) ? 
                "您的實名認證已通過審核！" : 
                "您的實名認證未通過審核，原因：" + event.rejectReason();
        notificationService.sendNotification(event.sitterId(), message);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSitterSuspended(com.petsitter.domain.event.SitterSuspendedEvent event) {
        log.info("Async notification - Sitter Suspended. Sitter: {}, Reason: {}", 
                event.sitterId(), event.reason());
        notificationService.sendNotification(event.sitterId(), "您的保母接單資格已被停權，原因：" + event.reason());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSitterUnsuspended(com.petsitter.domain.event.SitterUnsuspendedEvent event) {
        log.info("Async notification - Sitter Unsuspended. Sitter: {}", event.sitterId());
        notificationService.sendNotification(event.sitterId(), "您的保母接單資格已恢復，可至設定手動開啟接單狀態。");
    }
}
