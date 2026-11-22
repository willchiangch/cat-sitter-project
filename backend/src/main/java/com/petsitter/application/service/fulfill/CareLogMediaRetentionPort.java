package com.petsitter.application.service.fulfill;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 給 SD-CARE / SD-ORDER 的日誌媒體保留介面（SD-FULFILL §2.3.2，PRD-013），取代他模組直讀 {@code service_report_media}。
 */
public interface CareLogMediaRetentionPort {

    record ExpiredMediaView(UUID mediaId, String objectKey, Long objectGeneration, UUID orderId) {
    }

    /** 保留期滿、尚未清除的媒體（所屬訂單結案日 + 快照保留天數 ≤ now，-1 永久除外）。{@code SUPPORTS}。 */
    List<ExpiredMediaView> findExpiredMedia(OffsetDateTime now, int limit);

    /** 同交易：媒體 {@code is_purged = true}、{@code purged_at}；對應槽位寫 {@code object_deleted_at}。不鎖訂單。{@code REQUIRED}。 */
    void markPurged(UUID mediaId, OffsetDateTime now);

    /** 仍有 {@code is_purged = false} 媒體的訂單（單次查詢）。{@code SUPPORTS}。 */
    Set<UUID> hasUnpurgedMedia(Set<UUID> orderIds);
}
