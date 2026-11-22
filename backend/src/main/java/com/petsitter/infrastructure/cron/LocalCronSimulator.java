package com.petsitter.infrastructure.cron;

import com.petsitter.application.service.NotificationCleanupService;
import com.petsitter.application.service.fulfill.ServiceReportUploadOrphanCronService;
import com.petsitter.application.service.fulfill.VisitTimeoutCronService;
import com.petsitter.application.service.order.OrderCronService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 僅在本地開發時模擬 Cloud Scheduler（SD-ORDER §8.1 / M27）：每 15 分鐘依序呼叫 C1–C8，每日呼叫 C9、C10；SD-FULFILL FC1、FC2 依各自分鐘。
 * 測試設定以 {@code app.local-cron.enabled=false} 關閉，避免排程在整合測試中途推進資料。
 */
@Slf4j
@Component
@Profile("local")
@EnableScheduling
@ConditionalOnProperty(name = "app.local-cron.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class LocalCronSimulator {

    private final OrderCronService orderCronService;
    private final NotificationCleanupService notificationCleanupService;
    private final VisitTimeoutCronService visitTimeoutCronService;
    private final ServiceReportUploadOrphanCronService orphanCronService;

    /** SD-FULFILL FC1（{@code 7-59/15}）。 */
    @Scheduled(cron = "0 7-59/15 * * * *", zone = "Asia/Taipei")
    public void simulateVisitTimeout() {
        visitTimeoutCronService.run(null);
    }

    /** SD-FULFILL FC2（{@code 27 * * * *}）。 */
    @Scheduled(cron = "0 27 * * * *", zone = "Asia/Taipei")
    public void simulateCareLogOrphans() {
        orphanCronService.run(null);
    }

    @Scheduled(cron = "0 */15 * * * *")
    public void simulateOrderCrons() {
        log.info("[LocalCronSimulator] C1–C8");
        orderCronService.reviewExpiry(null);
        orderCronService.paymentReminder(null);
        orderCronService.initialPaymentExpiry(null);
        orderCronService.paymentEscalation(null);
        orderCronService.modificationExpiry(null);
        orderCronService.supplementExpiry(null);
        orderCronService.zombieVisits(null);
        orderCronService.autoComplete(null);
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void simulateDailyCleanups() {
        log.info("[LocalCronSimulator] C9 / C10 / notification cleanup");
        orderCronService.idempotencyCleanup();
        orderCronService.paymentProofOrphans();
        notificationCleanupService.cleanupOldNotifications();
    }
}
