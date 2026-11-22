package com.petsitter.domain.repository.fulfill;

import com.petsitter.domain.model.fulfill.ServiceReportMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SD-FULFILL 擁有；只准 {@code application.service.fulfill} 依賴（FulfillAggregateArchTest）。 */
public interface ServiceReportMediaRepository extends JpaRepository<ServiceReportMedia, UUID> {

    List<ServiceReportMedia> findByReportIdOrderByUploadedAtAscIdAsc(UUID reportId);

    List<ServiceReportMedia> findByReportIdIn(Collection<UUID> reportIds);

    long countByReportIdAndMediaType(UUID reportId, String mediaType);

    Optional<ServiceReportMedia> findByUploadId(UUID uploadId);

    /** 鎖前投影：媒體所屬行程（media → report → visit_id），G4。 */
    @Query("SELECT r.visitId FROM ServiceReportMedia m JOIN VisitServiceReport r ON r.id = m.reportId WHERE m.id = :mediaId")
    Optional<UUID> findVisitIdById(@Param("mediaId") UUID mediaId);

    /** 鎖前預檢：行程日誌某類型媒體數。 */
    @Query("SELECT count(m) FROM ServiceReportMedia m JOIN VisitServiceReport r ON r.id = m.reportId WHERE r.visitId = :visitId AND m.mediaType = :mediaType")
    long countByVisitIdAndMediaType(@Param("visitId") UUID visitId, @Param("mediaType") String mediaType);

    /** PRD-013 到期媒體候選（CareLogMediaRetentionPort.findExpiredMedia；唯讀 JOIN visits / orders / order_snapshots 的排程候選例外）。 */
    interface ExpiredMediaRow {
        UUID getMediaId();

        String getObjectKey();

        Long getObjectGeneration();

        UUID getOrderId();
    }

    @Query(value = """
            SELECT srm.id AS mediaId, srm.object_key AS objectKey, srm.object_generation AS objectGeneration, v.order_id AS orderId
            FROM service_report_media srm
            JOIN visit_service_reports sr ON srm.report_id = sr.id
            JOIN visits v ON sr.visit_id = v.id
            JOIN orders o ON v.order_id = o.id
            JOIN order_snapshots os ON os.order_id = o.id
            WHERE o.status = 'COMPLETED'
              AND srm.is_purged = false
              AND os.media_retention_days <> -1
              AND o.completed_at + (os.media_retention_days || ' day')::interval <= :now
            ORDER BY srm.id
            LIMIT :limit
            """, nativeQuery = true)
    List<ExpiredMediaRow> findExpired(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    /** 仍有未清除媒體的訂單（hasUnpurgedMedia，單次查詢）。 */
    @Query(value = """
            SELECT DISTINCT v.order_id FROM service_report_media srm
            JOIN visit_service_reports sr ON srm.report_id = sr.id
            JOIN visits v ON sr.visit_id = v.id
            WHERE v.order_id IN (:orderIds) AND srm.is_purged = false
            """, nativeQuery = true)
    List<UUID> findOrderIdsWithUnpurgedMedia(@Param("orderIds") Collection<UUID> orderIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE service_report_media SET is_purged = true, purged_at = :now, updated_at = :now WHERE id = :id AND is_purged = false",
            nativeQuery = true)
    int markPurged(@Param("id") UUID id, @Param("now") OffsetDateTime now);
}
