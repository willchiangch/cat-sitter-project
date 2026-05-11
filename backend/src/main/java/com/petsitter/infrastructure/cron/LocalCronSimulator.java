package com.petsitter.infrastructure.cron;

import com.petsitter.application.service.CompletionService;
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

    /**
     * 每小時執行一次自動結案邏輯模擬 (本地開發使用)
     * 這裡直接呼叫 Service 邏輯，或可以透過 RestTemplate 呼叫 /api/internal/... 模擬更真實。
     * 為了簡單起見，直接呼叫 Service。
     */
    @Scheduled(cron = "0 0 * * * *")
    public void simulateAutoCompletion() {
        log.info("[LocalCronSimulator] Simulating external cron trigger for auto-completion");
        completionService.triggerAutoCompletion();
    }
}
