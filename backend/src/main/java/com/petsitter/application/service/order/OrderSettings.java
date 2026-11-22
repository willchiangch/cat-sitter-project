package com.petsitter.application.service.order;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** {@code app.order.*} 參數（SD-ORDER §8.2；皆可由環境變數覆寫）。 */
@Getter
@Component
public class OrderSettings {

    @Value("${app.order.urgent-window-hours:2}")
    private long urgentWindowHours;

    @Value("${app.order.payment-reminder-lead-hours:48}")
    private long paymentReminderLeadHours;

    @Value("${app.order.modification-timeout-hours:72}")
    private long modificationTimeoutHours;

    @Value("${app.order.visit-zombie-hours:72}")
    private long visitZombieHours;

    @Value("${app.order.auto-complete-hours:48}")
    private long autoCompleteHours;

    @Value("${app.order.dispute-window-hours:48}")
    private long disputeWindowHours;

    /** 讀 SD-FULFILL 設定（不重複定義）；B 階段 FULFILL 尚未有自己的設定鍵，預設 24。 */
    @Value("${app.fulfill.care-log-edit-hours:24}")
    private long careLogEditHours;

    @Value("${app.order.proof-max-bytes:5242880}")
    private long proofMaxBytes;

    @Value("${app.order.proof-url-ttl:PT5M}")
    private Duration proofUrlTtl;

    @Value("${app.order.cron.batch-size:200}")
    private int cronBatchSize;

    @Value("${app.order.cron.time-budget:PT50S}")
    private Duration cronTimeBudget;

    @Value("${app.order.max-amount:999999}")
    private int maxAmount;

    public Duration urgentWindow() {
        return Duration.ofHours(urgentWindowHours);
    }

    public Duration modificationTimeout() {
        return Duration.ofHours(modificationTimeoutHours);
    }

    public Duration careLogEdit() {
        return Duration.ofHours(careLogEditHours);
    }

    public Duration autoComplete() {
        return Duration.ofHours(autoCompleteHours);
    }

    public Duration disputeWindow() {
        return Duration.ofHours(disputeWindowHours);
    }

    public Duration visitZombie() {
        return Duration.ofHours(visitZombieHours);
    }

    public Duration paymentReminderLead() {
        return Duration.ofHours(paymentReminderLeadHours);
    }
}
