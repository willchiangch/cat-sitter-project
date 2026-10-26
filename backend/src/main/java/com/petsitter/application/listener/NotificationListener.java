package com.petsitter.application.listener;

import com.petsitter.application.event.VisitNotificationEvent;
import com.petsitter.application.service.NotificationService;
import com.petsitter.domain.event.KycReviewedEvent;
import com.petsitter.domain.event.OrderCancelledByOwnerEvent;
import com.petsitter.domain.event.OrderRejectedBySitterEvent;
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

    /**
     * 通知 linkUrl 必須是前端實際存在的路由（frontend/src/routes.tsx）。過去飼主端寫成不存在的
     * /client/orders、/visits/report?orderId=，前端只能靠硬編碼對照轉址，對照漏掉的就點了沒反應。
     * 飼主端訂單相關通知一律導到該筆訂單詳情 /owner/orders/{orderId}（付款被退回要在這頁重傳憑證）。
     * 迴歸：NotificationListenerLinkTest 逐一比對 routes.tsx。
     */
    static String ownerOrderLink(java.util.UUID orderId) {
        return "/owner/orders/" + orderId;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentProofSubmitted(PaymentProofSubmittedEvent event) {
        log.info("Async notification - Payment Proof Submitted for Order: {}, Owner: {}, objectKey: {}", 
                event.orderId(), event.ownerId(), event.paymentProofObjectKey());
        
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

    /**
     * 飼主於付款前撤單（PRD-006 例外處理 / fix-batch10 H5）：通知保母，類別沿用訂單事務 ORDER_AFFAIR
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCancelledByOwner(OrderCancelledByOwnerEvent event) {
        log.info("Async notification - Order {} cancelled by owner {} (was {})",
                event.orderId(), event.ownerId(), event.previousStatus());

        Order order = orderRepository.findById(event.orderId()).orElse(null);
        if (order != null) {
            notificationService.createNotification(
                    order.getSitter().getId(),
                    "飼主已取消預約",
                    "飼主已取消訂單 #" + event.orderId().toString().substring(0, 8) + " 的預約，無需再處理此筆訂單。",
                    "ORDER_AFFAIR",
                    "/sitter/orders",
                    "SITTER"
            );
        }
    }

    /**
     * 保母婉拒待評估訂單（PRD-006 主流程 D / fix-batch13 K1）：通知飼主，內容帶婉拒原因，連到該筆訂單詳情。
     * 類別沿用訂單事務 ORDER_AFFAIR（同撤單通知），Email 依飼主的通知偏好由 NotificationService 決定
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderRejectedBySitter(OrderRejectedBySitterEvent event) {
        log.info("Async notification - Order {} rejected by sitter {}", event.orderId(), event.sitterId());

        Order order = orderRepository.findById(event.orderId()).orElse(null);
        if (order != null) {
            notificationService.createNotification(
                    order.getOwner().getId(),
                    "預約已婉拒",
                    orderRejectedContent(event),
                    "ORDER_AFFAIR",
                    ownerOrderLink(event.orderId()),
                    "OWNER"
            );
        }
    }

    static String orderRejectedContent(OrderRejectedBySitterEvent event) {
        String head = "保母已婉拒您的預約（訂單 #" + event.orderId().toString().substring(0, 8) + "）";
        String reason = event.rejectReason();
        return reason == null || reason.isBlank()
                ? head + "，保母未填寫原因。"
                : head + "，原因：" + reason.trim();
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentVerified(PaymentVerifiedEvent event) {
        log.info("Async notification - Payment Verified for Order: {}, Sitter: {}, supplement: {}",
                event.orderId(), event.sitterId(), event.supplement());
        
        Order order = orderRepository.findById(event.orderId()).orElse(null);
        if (order != null) {
            // 補款核帳時訂單早已成立，只是變更差額結清；沿用 ORDER_AFFAIR（同屬訂單事務，偏好設定不需新增類別）
            String title = event.supplement() ? "補款已確認" : "付款已確認";
            String content = event.supplement()
                    ? "您的訂單變更補款已通過保母確認，變更內容正式生效。"
                    : "您的訂單付款已通過保母確認，交易正式成立。";
            notificationService.createNotification(
                    order.getOwner().getId(),
                    title,
                    content,
                    "ORDER_AFFAIR",
                    ownerOrderLink(event.orderId()),
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
                    ownerOrderLink(event.orderId()),
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
                ownerOrderLink(event.orderId()),
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
                ownerOrderLink(event.getOrderId()),
                "OWNER"
        );
    }
}

