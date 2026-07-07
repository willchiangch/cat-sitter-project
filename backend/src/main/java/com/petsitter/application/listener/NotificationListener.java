package com.petsitter.application.listener;

import com.petsitter.application.event.VisitNotificationEvent;
import com.petsitter.application.service.NotificationService;
import com.petsitter.domain.event.KycReviewedEvent;
import com.petsitter.domain.event.PaymentProofSubmittedEvent;
import com.petsitter.domain.event.PaymentRejectedEvent;
import com.petsitter.domain.event.PaymentVerifiedEvent;
import com.petsitter.domain.event.SitterSuspendedEvent;
import com.petsitter.domain.event.SitterUnsuspendedEvent;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.repository.OrderRepository;
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
    private final OrderRepository orderRepository;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentProofSubmitted(PaymentProofSubmittedEvent event) {
        log.info("Async notification - Payment Proof Submitted for Order: {}, Owner: {}, URL: {}", 
                event.orderId(), event.ownerId(), event.paymentProofUrl());
        
        Order order = orderRepository.findById(event.orderId()).orElse(null);
        if (order != null) {
            notificationService.createNotification(
                    order.getSitter().getId(),
                    "收到付款憑證",
                    "飼主已上傳訂單的線下付款憑證，請前往核對並入帳。",
                    "ORDER_AFFAIR",
                    "/sitter/orders",
                    "SITTER"
            );
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentVerified(PaymentVerifiedEvent event) {
        log.info("Async notification - Payment Verified for Order: {}, Sitter: {}", 
                event.orderId(), event.sitterId());
        
        Order order = orderRepository.findById(event.orderId()).orElse(null);
        if (order != null) {
            notificationService.createNotification(
                    order.getOwner().getId(),
                    "付款已確認",
                    "您的訂單付款已通過保母確認，交易正式成立。",
                    "ORDER_AFFAIR",
                    "/client/orders",
                    "OWNER"
            );
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentRejected(PaymentRejectedEvent event) {
        log.info("Async notification - Payment Rejected for Order: {}, Sitter: {}, Reason: {}", 
                event.orderId(), event.sitterId(), event.rejectReason());
        
        Order order = orderRepository.findById(event.orderId()).orElse(null);
        if (order != null) {
            notificationService.createNotification(
                    order.getOwner().getId(),
                    "付款憑證退回",
                    "您的訂單線下付款憑證被保母退回，原因：" + event.rejectReason() + "，請前往修正。",
                    "ORDER_AFFAIR",
                    "/client/orders",
                    "OWNER"
            );
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleVisitNotification(VisitNotificationEvent event) {
        log.info("Async notification - Visit Notification for User: {}, Msg: {}", event.userId(), event.message());
        notificationService.createNotification(
                event.userId(),
                "照護日誌回報",
                event.message(),
                "SERVICE_RECORD",
                "/client/orders",
                "OWNER"
        );
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onKycReviewed(KycReviewedEvent event) {
        log.info("Async notification - KYC Reviewed for record: {}, Sitter: {}, status: {}, reason: {}", 
                event.recordId(), event.sitterId(), event.status(), event.rejectReason());
        
        boolean approved = "APPROVED".equals(event.status());
        String content = approved ? 
                "您的實名認證已通過審核！" : 
                "您的實名認證未通過審核，原因：" + event.rejectReason();
        
        notificationService.createNotification(
                event.sitterId(),
                "實名認證審核結果",
                content,
                "ACCOUNT_AUTH",
                "/sitter/kyc",
                "SITTER"
        );
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSitterSuspended(SitterSuspendedEvent event) {
        log.info("Async notification - Sitter Suspended. Sitter: {}, Reason: {}", 
                event.sitterId(), event.reason());
        
        notificationService.createNotification(
                event.sitterId(),
                "接單資格停權通知",
                "您的保母接單資格已被系統暫停，原因：" + event.reason() + "。如有疑問請聯絡客服。",
                "ACCOUNT_AUTH",
                "/sitter/kyc",
                "SITTER"
        );
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSitterUnsuspended(SitterUnsuspendedEvent event) {
        log.info("Async notification - Sitter Unsuspended. Sitter: {}", event.sitterId());
        
        notificationService.createNotification(
                event.sitterId(),
                "接單資格恢復通知",
                "您的保母接單資格已恢復，可至設定手動開啟接單狀態。",
                "ACCOUNT_AUTH",
                "/sitter/kyc",
                "SITTER"
        );
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMediaExpiryWarning(com.petsitter.domain.event.MediaExpiryWarningEvent event) {
        log.info("Async notification - Media Expiry Warning. Order: {}, Owner: {}", event.getOrderId(), event.getOwnerId());
        
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String dateStr = event.getExpiryTime().format(formatter);
        String content = "您的服務報告內含照片/影片將於 " + dateStr + " 自動移除，請儘速下載備份。";

        notificationService.createNotification(
                event.getOwnerId(),
                "照護影像即將過期提醒",
                content,
                "SERVICE_RECORD",
                "/visits/report?orderId=" + event.getOrderId(),
                "OWNER"
        );
    }
}

