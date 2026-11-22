package com.petsitter.domain.repository.fulfill;

import com.petsitter.domain.model.fulfill.ServiceReportUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * SD-FULFILL 擁有；只准 {@code application.service.fulfill} 依賴（FulfillAggregateArchTest）。
 * 槽位轉移一律條件式 UPDATE（§3.3 互斥）：回傳影響列數，0 = 已被他方轉移，呼叫端不得做後續副作用。
 */
public interface ServiceReportUploadRepository extends JpaRepository<ServiceReportUpload, UUID> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE care_log_media_uploads SET status = 'COMPLETED', completed_at = :now, updated_at = :now
            WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int markCompleted(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE care_log_media_uploads SET status = 'FAILED', failure_status = :failureStatus, failure_code = :failureCode,
                   failure_payload = CAST(:failurePayload AS jsonb), updated_at = :now
            WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int markFailed(@Param("id") UUID id, @Param("failureStatus") short failureStatus, @Param("failureCode") String failureCode,
                   @Param("failurePayload") String failurePayload, @Param("now") OffsetDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE care_log_media_uploads SET status = 'DISCARDED', updated_at = :now
            WHERE id = :id AND status = 'COMPLETED'
            """, nativeQuery = true)
    int markDiscarded(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE care_log_media_uploads SET status = 'EXPIRED', updated_at = :now
            WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int markExpired(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE care_log_media_uploads SET object_deleted_at = :now, updated_at = :now
            WHERE id = :id AND object_deleted_at IS NULL
            """, nativeQuery = true)
    int markObjectDeleted(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE care_log_media_uploads SET delete_attempts = LEAST(delete_attempts + 1, 32767), updated_at = :now
            WHERE id = :id
            """, nativeQuery = true)
    int incrementDeleteAttempts(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    /** 鎖前投影：槽位所屬行程與保母（G4 / G5）。 */
    interface UploadRef {
        UUID getId();

        UUID getVisitId();

        UUID getSitterId();
    }

    @Query("SELECT u.id AS id, u.visitId AS visitId, u.sitterId AS sitterId FROM ServiceReportUpload u WHERE u.id = :id")
    java.util.Optional<UploadRef> findRef(@Param("id") UUID id);

    /** FC2 ①：PENDING 且 {@code expires_at <= now − 寬限}（ix_uploads_cleanup；自動觸發排除測試帳號訂單）。 */
    @Query(value = """
            SELECT u.id FROM care_log_media_uploads u
            WHERE u.status = 'PENDING' AND u.object_deleted_at IS NULL AND u.expires_at <= :cutoff
              AND (CAST(:orderId AS uuid) IS NULL OR u.order_id = CAST(:orderId AS uuid))
              AND (CAST(:orderId AS uuid) IS NOT NULL OR NOT EXISTS (SELECT 1 FROM orders o JOIN users us
                     ON us.id IN (o.owner_id, o.sitter_id) WHERE o.id = u.order_id AND us.email LIKE '%@e2e-journey.test'))
            ORDER BY u.expires_at
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findExpirableIds(@Param("cutoff") OffsetDateTime cutoff, @Param("orderId") UUID orderId, @Param("limit") int limit);

    /** FC2 ②：FAILED / EXPIRED / DISCARDED、未最終清除、{@code expires_at <= now − 寬限}。 */
    @Query(value = """
            SELECT u.* FROM care_log_media_uploads u
            WHERE u.status IN ('FAILED', 'EXPIRED', 'DISCARDED') AND u.object_deleted_at IS NULL AND u.expires_at <= :cutoff
              AND (CAST(:orderId AS uuid) IS NULL OR u.order_id = CAST(:orderId AS uuid))
              AND (CAST(:orderId AS uuid) IS NOT NULL OR NOT EXISTS (SELECT 1 FROM orders o JOIN users us
                     ON us.id IN (o.owner_id, o.sitter_id) WHERE o.id = u.order_id AND us.email LIKE '%@e2e-journey.test'))
            ORDER BY u.expires_at
            LIMIT :limit
            """, nativeQuery = true)
    List<ServiceReportUpload> findCleanupCandidates(@Param("cutoff") OffsetDateTime cutoff, @Param("orderId") UUID orderId,
                                                    @Param("limit") int limit);
}
