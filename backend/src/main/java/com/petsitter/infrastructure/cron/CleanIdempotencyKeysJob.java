package com.petsitter.infrastructure.cron;

import com.petsitter.domain.repository.IdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Slf4j
@Component
@RequiredArgsConstructor
public class CleanIdempotencyKeysJob {

    private final IdempotencyKeyRepository idempotencyKeyRepository;

    @Scheduled(cron = "0 0 3 * * ?") // 每天凌晨 3 點執行
    @Transactional
    public void cleanExpiredKeys() {
        OffsetDateTime cutoffTime = OffsetDateTime.now(ZoneOffset.UTC).minusHours(24);
        log.info("Starting scheduled job to clean idempotency keys older than {}", cutoffTime);
        
        int deletedCount = idempotencyKeyRepository.deleteOldKeys(cutoffTime);
        
        log.info("Finished cleaning idempotency keys. Deleted count: {}", deletedCount);
    }
}
