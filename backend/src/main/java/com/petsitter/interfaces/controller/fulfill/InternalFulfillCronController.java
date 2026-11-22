package com.petsitter.interfaces.controller.fulfill;

import com.petsitter.application.service.fulfill.ServiceReportUploadOrphanCronService;
import com.petsitter.application.service.fulfill.VisitTimeoutCronService;
import com.petsitter.application.service.order.OrderCronService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 排程端點 FC1、FC2（SD-FULFILL §6.3 / §8.1）。{@code /api/internal/**} 由 {@code InternalSecretFilter} 驗證
 * {@code X-Internal-Secret}（不符 403）。可選 {@code orderId} 只處理該測試帳號訂單（非測試帳號 → 404）；
 * 無值時候選排除 {@code @e2e-journey.test} 帳號的訂單 / 槽位。
 */
@RestController
@RequestMapping("/api/internal/cron")
@RequiredArgsConstructor
public class InternalFulfillCronController {

    private final VisitTimeoutCronService timeoutCronService;
    private final ServiceReportUploadOrphanCronService orphanCronService;

    /** FC1（Asia/Taipei {@code 7-59/15 * * * *}）。 */
    @PostMapping("/visits/in-progress-timeout")
    public OrderCronService.CronResult inProgressTimeout(@RequestParam(required = false) UUID orderId) {
        return timeoutCronService.run(orderId);
    }

    /** FC2（Asia/Taipei {@code 27 * * * *}）。 */
    @PostMapping("/storage/care-log-orphans")
    public OrderCronService.CronResult careLogOrphans(@RequestParam(required = false) UUID orderId) {
        return orphanCronService.run(orderId);
    }
}
