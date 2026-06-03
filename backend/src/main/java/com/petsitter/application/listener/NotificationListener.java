package com.petsitter.application.listener;

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
}
