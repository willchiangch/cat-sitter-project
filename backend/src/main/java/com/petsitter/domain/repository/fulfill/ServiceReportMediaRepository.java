package com.petsitter.domain.repository.fulfill;

import com.petsitter.domain.model.fulfill.ServiceReportMedia;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ServiceReportMediaRepository extends JpaRepository<ServiceReportMedia, UUID> {

    List<ServiceReportMedia> findByReportIdOrderByUploadedAtAsc(UUID reportId);

    int countByReportIdAndMediaType(UUID reportId, String mediaType);

    /**
     * PRD-013 到期媒體（唯讀 JOIN visits / orders / order_snapshots 的排程候選例外，SD-FULFILL §2.3.2）。
     * TODO(C 階段 F-M22)：改為 CareLogMediaRetentionPort.findExpiredMedia。
     */
    @Query(value = """
        SELECT srm.* FROM service_report_media srm
        JOIN visit_service_reports sr ON srm.report_id = sr.id
        JOIN visits v ON sr.visit_id = v.id
        JOIN orders o ON v.order_id = o.id
        JOIN order_snapshots os ON os.order_id = o.id
        WHERE o.status = 'COMPLETED'
          AND srm.is_purged = false
          AND os.media_retention_days != -1
          AND o.completed_at + (os.media_retention_days || ' day')::interval < :now
        """, nativeQuery = true)
    List<ServiceReportMedia> findExpiredMedia(@Param("now") OffsetDateTime now, Pageable pageable);

    /** 仍有未清除媒體的訂單（SD-FULFILL §2.3.2 hasUnpurgedMedia，M32）。 */
    @Query(value = """
        SELECT DISTINCT v.order_id FROM service_report_media srm
        JOIN visit_service_reports sr ON srm.report_id = sr.id
        JOIN visits v ON sr.visit_id = v.id
        WHERE v.order_id IN (:orderIds) AND srm.is_purged = false
        """, nativeQuery = true)
    List<UUID> findOrderIdsWithUnpurgedMedia(@Param("orderIds") Collection<UUID> orderIds);
}
