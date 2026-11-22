package com.petsitter.application.service.fulfill;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** {@code app.fulfill.*} 參數（SD-FULFILL §8.2；皆可由環境變數覆寫）。殭屍期限讀 SD-ORDER {@code app.order.visit-zombie-hours}。 */
@Getter
@Component
public class FulfillSettings {

    @Value("${app.fulfill.care-log-edit-hours:24}")
    private long careLogEditHours;

    @Value("${app.fulfill.visit-in-progress-timeout-hours:24}")
    private long visitInProgressTimeoutHours;

    @Value("${app.order.visit-zombie-hours:72}")
    private long visitZombieHours;

    @Value("${app.fulfill.visit-name-max:20}")
    private int visitNameMax;

    @Value("${app.fulfill.checklist-title-max:50}")
    private int checklistTitleMax;

    @Value("${app.fulfill.checklist-max-items:20}")
    private int checklistMaxItems;

    @Value("${app.fulfill.care-log-text-max:1000}")
    private int careLogTextMax;

    @Value("${app.fulfill.caption-max:100}")
    private int captionMax;

    @Value("${app.fulfill.photo-max-bytes-exclusive:1048576}")
    private long photoMaxBytesExclusive;

    @Value("${app.fulfill.video-max-bytes-exclusive:52428800}")
    private long videoMaxBytesExclusive;

    @Value("${app.fulfill.video-min-seconds:15}")
    private int videoMinSeconds;

    @Value("${app.fulfill.video-max-seconds:30}")
    private int videoMaxSeconds;

    @Value("${app.fulfill.upload-slot-ttl:PT30M}")
    private Duration uploadSlotTtl;

    @Value("${app.fulfill.upload-url-ttl:PT15M}")
    private Duration uploadUrlTtl;

    @Value("${app.fulfill.upload-delete-grace:PT15M}")
    private Duration uploadDeleteGrace;

    @Value("${app.fulfill.media-url-ttl:PT15M}")
    private Duration mediaUrlTtl;

    @Value("${app.fulfill.media-url-cache-ttl:PT5M}")
    private Duration mediaUrlCacheTtl;

    @Value("${app.fulfill.orphan-delete-alert-attempts:5}")
    private int orphanDeleteAlertAttempts;

    @Value("${app.fulfill.cron.batch-size:200}")
    private int cronBatchSize;

    @Value("${app.fulfill.cron.time-budget:PT50S}")
    private Duration cronTimeBudget;

    public Duration careLogEdit() {
        return Duration.ofHours(careLogEditHours);
    }

    public Duration inProgressTimeout() {
        return Duration.ofHours(visitInProgressTimeoutHours);
    }

    public Duration visitZombie() {
        return Duration.ofHours(visitZombieHours);
    }
}
