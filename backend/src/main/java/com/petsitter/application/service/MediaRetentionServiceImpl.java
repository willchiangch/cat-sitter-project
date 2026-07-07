package com.petsitter.application.service;

import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.OrderSnapshot;
import com.petsitter.domain.model.ServiceReportMedia;
import com.petsitter.domain.repository.OrderRepository;
import com.petsitter.domain.repository.OrderSnapshotRepository;
import com.petsitter.domain.repository.ServiceReportMediaRepository;
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
    private final OrderRepository orderRepository;
    private final OrderSnapshotRepository orderSnapshotRepository;
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
                    mediaStorageService.deleteMedia(media.getMediaUrl());

                    // 2. 跨 Bean 呼叫標記 DB 刪除 (REQUIRES_NEW 獨立 Commit)
                    batchDeleter.markAsPurged(media.getId());
                    deletedCount++;
                } catch (Exception e) {
                    log.error("Failed to purge media file: " + media.getMediaUrl(), e);
                }
            }
        }
        return deletedCount;
    }

    @Override
    public int sendExpiryWarnings() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<Order> pendingOrders = orderRepository.findOrdersPendingMediaExpiryWarning(now);
        int warnedCount = 0;

        for (Order order : pendingOrders) {
            try {
                // 3. 呼叫 REQUIRES_NEW 的處理器 (單筆獨立事務提交，失敗不阻塞下一筆)
                warningProcessor.processWarning(order);
                warnedCount++;
            } catch (Exception e) {
                log.error("Failed to send expiry warning for order: " + order.getId(), e);
            }
        }
        return warnedCount;
    }

    @Override
    @Transactional
    public void upgradeSitterMediaRetention(UUID sitterId, UUID operatorId, String newPlanTier) {
        PlanTier tier = PlanTier.fromString(newPlanTier);
        int newRetentionDays = tier.getMediaRetentionDays();

        // 1. 查詢該保母名下所有已完成之訂單快照
        List<OrderSnapshot> snapshots = orderSnapshotRepository.findActiveSnapshotsForUpgrade(sitterId);
        
        for (OrderSnapshot snapshot : snapshots) {
            int current = snapshot.getMediaRetentionDays();
            // 降級保護：新天數為永久 (-1)，或者 (舊天數不是永久，且新天數比舊天數長) 才允許更新天數
            boolean isExtension = newRetentionDays == -1 || (current != -1 && newRetentionDays > current);
            if (!isExtension) {
                continue; // 降級或更短的合約不予以縮短既有保留天數
            }
            snapshot.setMediaRetentionDays(newRetentionDays);
            snapshot.setPlanTier(newPlanTier);
            orderSnapshotRepository.save(snapshot);
        }
        
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
