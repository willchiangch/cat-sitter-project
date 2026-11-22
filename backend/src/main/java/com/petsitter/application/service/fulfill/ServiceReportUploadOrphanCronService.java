package com.petsitter.application.service.fulfill;

import com.petsitter.application.service.MediaStorageService;
import com.petsitter.application.service.order.OrderClock;
import com.petsitter.application.service.order.OrderCronService;
import com.petsitter.domain.model.fulfill.ServiceReportUpload;
import com.petsitter.domain.repository.fulfill.ServiceReportUploadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * FC2 日誌上傳孤兒檔清除（SD-FULFILL §3.3、§8.1）：
 * ① PENDING 且 {@code expires_at <= now − 寬限} → 條件式 UPDATE 為 EXPIRED（不搶確認窗內已收到、仍在串流的 F18）；
 * ② FAILED / EXPIRED / DISCARDED、未最終清除、{@code expires_at <= now − 寬限} → 刪 GCS 物件（不存在視為成功）→ 短交易寫
 * {@code object_deleted_at}；失敗 {@code delete_attempts + 1}，達門檻起每次記 ERROR log。不鎖訂單；計數以槽位為單位。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceReportUploadOrphanCronService {

    static final String JOB = "storage/care-log-orphans";

    private final OrderCronService orderCronService;
    private final ServiceReportUploadRepository uploadRepository;
    private final MediaStorageService storage;
    private final FulfillSettings settings;
    private final FulfillTx tx;
    private final OrderClock clock;

    public OrderCronService.CronResult run(UUID orderId) {
        CronScope scope = orderCronService.scope(orderId);
        OffsetDateTime started = clock.now();
        long deadline = System.nanoTime() + settings.getCronTimeBudget().toNanos();
        OffsetDateTime cutoff = started.minus(settings.getUploadDeleteGrace());
        int processed = 0;
        int skipped = 0;
        int failed = 0;
        for (UUID id : uploadRepository.findExpirableIds(cutoff, scope.orderId(), settings.getCronBatchSize())) {
            if (System.nanoTime() > deadline) {
                break;
            }
            Integer n = tx.write(() -> uploadRepository.markExpired(id, clock.now()));
            if (n != null && n == 1) {
                processed++;
            } else {
                skipped++;
            }
        }
        List<ServiceReportUpload> candidates = tx.readOnly(() ->
                uploadRepository.findCleanupCandidates(cutoff, scope.orderId(), settings.getCronBatchSize()));
        for (ServiceReportUpload u : candidates) {
            if (System.nanoTime() > deadline) {
                break;
            }
            try {
                storage.deletePrivateObject(u.getObjectKey(), null);
                tx.write(() -> uploadRepository.markObjectDeleted(u.getId(), clock.now()));
                processed++;
            } catch (RuntimeException e) {
                failed++;
                tx.write(() -> uploadRepository.incrementDeleteAttempts(u.getId(), clock.now()));
                int attempts = u.getDeleteAttempts() + 1;
                if (attempts >= settings.getOrphanDeleteAlertAttempts()) {
                    log.error("care log orphan delete failed {} times: uploadId={}", attempts, u.getId(), e);
                } else {
                    log.warn("care log orphan delete failed, retry next run: uploadId={}", u.getId());
                }
            }
        }
        return new OrderCronService.CronResult(JOB, processed, skipped, failed, started, clock.now());
    }
}
