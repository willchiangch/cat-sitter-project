package com.petsitter.application.listener;

import com.petsitter.application.service.NotificationService;
import com.petsitter.domain.event.KycReviewedEvent;
import com.petsitter.domain.event.SitterSuspendedEvent;
import com.petsitter.domain.event.SitterUnsuspendedEvent;
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

    // SD-ORDER M22：訂單事件（付款憑證、撤單、婉拒、付款確認 / 駁回）的 AFTER_COMMIT handler 已刪除，
    // 改由各轉移交易內 NotificationService.enqueue()。行程 / 日誌通知改由 SD-FULFILL 轉移交易內 enqueue（F-M9，handler 已刪除）。

    private final NotificationService notificationService;

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

