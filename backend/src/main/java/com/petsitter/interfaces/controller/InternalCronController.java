package com.petsitter.interfaces.controller;

import com.petsitter.application.service.CompletionService;
import com.petsitter.application.service.NotificationCleanupService;
import com.petsitter.application.service.TestDataCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/internal/cron")
@RequiredArgsConstructor
public class InternalCronController {

    private final CompletionService completionService;
    private final NotificationCleanupService notificationCleanupService;
    private final com.petsitter.application.service.MediaRetentionService mediaRetentionService;
    private final TestDataCleanupService testDataCleanupService;

    /**
     * 外部排程器觸發自動結案
     */
    @PostMapping("/orders/auto-complete")
    public ResponseEntity<Map<String, String>> triggerAutoComplete() {
        log.info("[InternalCronController] Received auto-complete trigger");
        completionService.triggerAutoCompletion();
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Auto-completion task triggered"));
    }

    /**
     * 外部排程器觸發物理清理 90 天前的通知
     */
    @PostMapping("/notifications/cleanup")
    public ResponseEntity<Map<String, Object>> triggerNotificationCleanup() {
        log.info("[InternalCronController] Received notification cleanup trigger");
        int deletedCount = notificationCleanupService.cleanupOldNotifications();
        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "message", "Notification cleanup task triggered",
                "deletedCount", deletedCount
        ));
    }

    /**
     * 外部排程器觸發物理清理過期日誌多媒體檔案 (SD-013)
     */
    @PostMapping("/media/cleanup")
    public ResponseEntity<Map<String, Object>> triggerMediaCleanup() {
        log.info("[InternalCronController] Received media cleanup trigger");
        int deletedCount = mediaRetentionService.cleanupExpiredMedia();
        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "message", "Media cleanup task completed",
                "deletedCount", deletedCount
        ));
    }

    /**
     * 外部排程器觸發發送多媒體到期前 3 天的提醒通知 (SD-013)
     */
    @PostMapping("/media/expiry-warning")
    public ResponseEntity<Map<String, Object>> triggerMediaExpiryWarning() {
        log.info("[InternalCronController] Received media expiry warning trigger");
        int warnedCount = mediaRetentionService.sendExpiryWarnings();
        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "message", "Media expiry warning task completed",
                "warnedCount", warnedCount
        ));
    }

    /**
     * CI E2E 測試跑完後觸發，軟刪除種子測試帳號 (sitter@test.com/owner@test.com) 累積的訂單
     */
    @PostMapping("/test-data/cleanup-seed-orders")
    public ResponseEntity<Map<String, Object>> cleanupSeedTestOrders() {
        log.info("[InternalCronController] Received seed test order cleanup trigger");
        int deletedCount = testDataCleanupService.cleanupSeedTestOrders();
        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "message", "Seed test order cleanup task completed",
                "deletedCount", deletedCount
        ));
    }
}

