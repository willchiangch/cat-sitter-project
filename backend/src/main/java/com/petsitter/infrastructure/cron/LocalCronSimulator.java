package com.petsitter.infrastructure.cron;

import com.petsitter.application.service.CompletionService;
import com.petsitter.application.service.NotificationCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 僅在本地開發時模擬外部排程器的定時觸發
 */
@Slf4j
@Component
@Profile("local")
@EnableScheduling
@RequiredArgsConstructor
public class LocalCronSimulator {

    private final CompletionService completionService;
    private final NotificationCleanupService notificationCleanupService;

    /**
     * 每小時執行一次自動結案邏輯模擬 (本地開發使用)
     */
    @Scheduled(cron = "0 0 * * * *")
    public void simulateAutoCompletion() {
        log.info("[LocalCronSimulator] Simulating external cron trigger for auto-completion");
        completionService.triggerAutoCompletion();
    }

    /**
     * 每天凌晨 3 點執行通知清理模擬 (本地開發使用)
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void simulateNotificationCleanup() {
        log.info("[LocalCronSimulator] Simulating external cron trigger for notification cleanup");
        notificationCleanupService.cleanupOldNotifications();
    }
}
