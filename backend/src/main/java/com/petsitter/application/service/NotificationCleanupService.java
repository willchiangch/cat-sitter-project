package com.petsitter.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationCleanupService {

    private final NotificationBatchDeleter batchDeleter;

    /**
     * 物理清理 90 天前的歷史通知
     * 故意不標記 @Transactional，以便讓 batchDeleter 內部的 REQUIRES_NEW 可以正常獨立提交各批次，防止 AOP 自我呼叫失效與大事務長鎖
     */
    public int cleanupOldNotifications() {
        OffsetDateTime cutoffTime = OffsetDateTime.now(ZoneOffset.UTC).minusDays(90);
        log.info("Starting batch notification cleanup. Cutoff time: {}", cutoffTime);
        
        int totalDeleted = 0;
        int deleted;
        do {
            deleted = batchDeleter.deleteBatch(cutoffTime);
            totalDeleted += deleted;
            log.debug("Deleted batch of notifications. Count: {}", deleted);
        } while (deleted > 0);
        
        log.info("Finished batch notification cleanup. Total deleted: {}", totalDeleted);
        return totalDeleted;
    }
}
