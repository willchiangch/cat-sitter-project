package com.petsitter.application.service;

import com.petsitter.application.service.fulfill.CareLogMediaRetentionPort;
import com.petsitter.application.service.order.OrderQueryService;
import com.petsitter.application.service.order.OrderSnapshotService;
import com.petsitter.infrastructure.security.gating.PlanTier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PRD-013 媒體保留（SD-CARE 範圍，未重建）。日誌媒體一律經 SD-FULFILL {@link CareLogMediaRetentionPort}
 * （F-M11 / F-M22），不直讀 {@code service_report_media}；刪 GCS 物件帶 generation 前置條件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaRetentionServiceImpl implements MediaRetentionService {

    private static final int BATCH = 500;

    private final CareLogMediaRetentionPort retentionPort;
    private final MediaStorageService mediaStorageService;
    private final OrderQueryService orderQueryService;
    private final OrderSnapshotService orderSnapshotService;
    private final MediaExpiryWarningBatchProcessor warningProcessor;
    private final AuditLogService auditLogService;
    private final Clock clock;

    @Override
    public int cleanupExpiredMedia() {
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        int deletedCount = 0;
        Set<UUID> failed = new HashSet<>();
        while (true) {
            List<CareLogMediaRetentionPort.ExpiredMediaView> expired = retentionPort.findExpiredMedia(now, BATCH + failed.size());
            List<CareLogMediaRetentionPort.ExpiredMediaView> todo = expired.stream().filter(m -> !failed.contains(m.mediaId())).toList();
            if (todo.isEmpty()) {
                return deletedCount;
            }
            for (CareLogMediaRetentionPort.ExpiredMediaView media : todo) {
                try {
                    // 交易外刪實體檔（帶 generation 前置條件），成功後短交易標記已清除
                    mediaStorageService.deletePrivateObject(media.objectKey(), media.objectGeneration());
                    retentionPort.markPurged(media.mediaId(), now);
                    deletedCount++;
                } catch (Exception e) {
                    failed.add(media.mediaId());
                    log.error("Failed to purge media file: {}", media.objectKey(), e);
                }
            }
        }
    }

    /**
     * SD-ORDER M32 / SD-FULFILL F-M22：候選拆兩步——訂單側（只讀 orders / order_snapshots）與
     * 「仍有未清除媒體」（{@link CareLogMediaRetentionPort#hasUnpurgedMedia}）。提醒時點沿用現行：到期前 3 天起。
     */
    @Override
    public int sendExpiryWarnings() {
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        List<UUID> candidates = orderQueryService.findCompletedOrdersPendingMediaExpiryWarning(
                now.minusYears(100), now.plusDays(3), BATCH);
        if (candidates.isEmpty()) {
            return 0;
        }
        Set<UUID> withMedia = retentionPort.hasUnpurgedMedia(new HashSet<>(candidates));
        Map<UUID, OrderQueryService.MediaExpiryView> views = orderQueryService.getMediaExpiryViews(withMedia);
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
