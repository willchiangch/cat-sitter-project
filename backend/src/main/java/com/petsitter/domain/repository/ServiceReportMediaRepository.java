package com.petsitter.domain.repository;

import com.petsitter.domain.model.ServiceReportMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ServiceReportMediaRepository extends JpaRepository<ServiceReportMedia, UUID> {
    List<ServiceReportMedia> findByReportIdAndIsDeletedFalse(UUID reportId);
    int countByReportIdAndMediaTypeAndIsDeletedFalse(UUID reportId, String mediaType);
    Optional<ServiceReportMedia> findByIdAndIsDeletedFalse(UUID id);

    @org.springframework.data.jpa.repository.Query(value = """
        SELECT srm.* FROM service_report_media srm
        JOIN visit_service_reports sr ON srm.report_id = sr.id
        JOIN visits v ON sr.visit_id = v.id
        JOIN orders o ON v.order_id = o.id
        JOIN order_snapshots os ON os.order_id = o.id
        WHERE o.status = 'COMPLETED'
          AND srm.is_purged = false
          AND srm.is_deleted = false
          AND os.media_retention_days != -1
          AND o.completed_at + (os.media_retention_days || ' day')::interval < :now
        """, nativeQuery = true)
    List<ServiceReportMedia> findExpiredMedia(@org.springframework.data.repository.query.Param("now") java.time.OffsetDateTime now, org.springframework.data.domain.Pageable pageable);

}
