package com.petsitter.application.service;

import com.petsitter.application.service.order.OrderQueryService;
import com.petsitter.application.service.order.OrderSnapshotService;
import com.petsitter.domain.model.fulfill.ServiceReportMedia;
import com.petsitter.domain.repository.fulfill.ServiceReportMediaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import com.petsitter.infrastructure.security.gating.PlanTier;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaRetentionServiceImpl implements MediaRetentionService {

    private final ServiceReportMediaRepository mediaRepository;
    private final MediaStorageService mediaStorageService;
    private final MediaPurgeBatchDeleter batchDeleter;
    private final OrderQueryService orderQueryService;
    private final OrderSnapshotService orderSnapshotService;
    private final MediaExpiryWarningBatchProcessor warningProcessor;
    private final AuditLogService auditLogService;

    @Override
    public int cleanupExpiredMedia() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int deletedCount = 0;
        boolean hasMore = true;
        int limit = 500;

        while (hasMore) {
            List<ServiceReportMedia> expired = mediaRepository.findExpiredMedia(now, PageRequest.of(0, limit));
            if (expired.isEmpty()) {
                hasMore = false;
                continue;
            }

            for (ServiceReportMedia media : expired) {
                try {
                    // 1. 事務外執行實體儲存空間物理刪除 (deleteMedia)
                    mediaStorageService.deleteMedia(media.getObjectKey());

                    // 2. 跨 Bean 呼叫標記 DB 刪除 (REQUIRES_NEW 獨立 Commit)
                    batchDeleter.markAsPurged(media.getId());
                    deletedCount++;
                } catch (Exception e) {
                    log.error("Failed to purge media file: " + media.getObjectKey(), e);
                }
            }
        }
        return deletedCount;
    }

    /**
     * SD-ORDER M32 / SD-FULFILL F-M22：候選拆兩步——訂單側（只讀 orders / order_snapshots）與
     * 「仍有未清除媒體」（日誌媒體表，TODO(C 階段 F-M22) 改經 CareLogMediaRetentionPort.hasUnpurgedMedia）。
     * 提醒時點沿用現行：到期前 3 天起（{@code completed_at + 保留天數 <= now + 3 天}）。
     */
    @Override
    public int sendExpiryWarnings() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<UUID> candidates = orderQueryService.findCompletedOrdersPendingMediaExpiryWarning(
                now.minusYears(100), now.plusDays(3), 500);
        if (candidates.isEmpty()) {
            return 0;
        }
        java.util.Set<UUID> withMedia = new java.util.HashSet<>(mediaRepository.findOrderIdsWithUnpurgedMedia(candidates));
        java.util.Map<UUID, OrderQueryService.MediaExpiryView> views = orderQueryService.getMediaExpiryViews(withMedia);
        int warnedCount = 0;
        for (OrderQueryService.MediaExpiryView view : views.values()) {
            try {
                warningProcessor.processWarning(view);
                warnedCount++;
            } catch (Exception e) {
                log.error("Failed to send expiry warning for order: " + view.orderId(), e);
            }
        }
        return warnedCount;
    }

    @Override
    @Transactional
    public void upgradeSitterMediaRetention(UUID sitterId, UUID operatorId, String newPlanTier) {
        PlanTier tier = PlanTier.fromString(newPlanTier);
        int newRetentionDays = tier.getMediaRetentionDays();

        // SD-ORDER M16：只延長不縮短，經 OrderSnapshotService
        orderSnapshotService.extendMediaRetentionForSitter(sitterId, newRetentionDays);

        // 2. 寫入審計日誌 (5 參數簽章)
        // 參數順序: funcCode, actionType, operatorId, targetId, targetTable
        auditLogService.writeUserActionLog(
            "SITTER_MEDIA_RETENTION_EXTEND", 
            "UPDATE", 
            operatorId, 
            sitterId, 
            "order_snapshots"
        );
    }
}
