package com.petsitter.domain.repository.fulfill;

import com.petsitter.domain.model.fulfill.Visit;
import com.petsitter.domain.model.order.VisitStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SD-FULFILL 擁有；只准 {@code application.service.fulfill} 依賴（FulfillAggregateArchTest）。 */
public interface VisitRepository extends JpaRepository<Visit, UUID> {

    /** {@code listVisits} 一次查詢（LEFT JOIN 日誌狀態）的列。 */
    interface VisitWithLogRow {
        UUID getVisitId();

        UUID getOrderItemId();

        UUID getPlanId();

        LocalDate getServiceDate();

        VisitStatus getStatus();

        OffsetDateTime getCompletedAt();

        OffsetDateTime getClosedAt();

        String getCareLogStatus();
    }

    @Query("""
            SELECT v.id AS visitId, v.orderItemId AS orderItemId, v.planId AS planId, v.serviceDate AS serviceDate,
                   v.status AS status, v.completedAt AS completedAt, v.closedAt AS closedAt, r.status AS careLogStatus
            FROM Visit v LEFT JOIN VisitServiceReport r ON r.visitId = v.id
            WHERE v.orderId = :orderId
            ORDER BY v.serviceDate, v.sequenceInDay, v.id
            """)
    List<VisitWithLogRow> findRowsWithLog(@Param("orderId") UUID orderId);

    /** {@code effectiveServiceDateRanges} 的列。 */
    interface ServiceDateRangeRow {
        UUID getOrderId();

        LocalDate getFirstDate();

        LocalDate getLastDate();
    }

    /** 批次：各訂單排除 {@code excluded} 狀態後的首末行程日（E3 / E4 / E30 列表不 N+1）。 */
    @Query("""
            SELECT v.orderId AS orderId, MIN(v.serviceDate) AS firstDate, MAX(v.serviceDate) AS lastDate
            FROM Visit v
            WHERE v.orderId IN :orderIds AND v.status <> :excluded
            GROUP BY v.orderId
            """)
    List<ServiceDateRangeRow> findServiceDateRanges(@Param("orderIds") Collection<UUID> orderIds,
                                                    @Param("excluded") VisitStatus excluded);

    List<Visit> findByOrderIdOrderByServiceDateAscSequenceInDayAsc(UUID orderId);

    List<Visit> findByOrderIdAndStatus(UUID orderId, VisitStatus status);

    List<Visit> findByOrderItemId(UUID orderItemId);

    /** 鎖前投影：行程歸屬（SD-FULFILL §2.5 第 1 點）。 */
    @Query("SELECT new com.petsitter.domain.repository.fulfill.VisitRef(v.id, v.orderId, v.sitterId, v.ownerId) FROM Visit v WHERE v.id = :visitId")
    Optional<VisitRef> findRef(@Param("visitId") UUID visitId);

    /** F6 今日：status ≠ CANCELLED。 */
    List<Visit> findBySitterIdAndServiceDateAndStatusNot(UUID sitterId, LocalDate serviceDate, VisitStatus status);

    /** F6 待處理：service_date &lt; 今天、PENDING / IN_PROGRESS。 */
    List<Visit> findBySitterIdAndServiceDateBeforeAndStatusIn(UUID sitterId, LocalDate serviceDate, Collection<VisitStatus> statuses);

    /** F7 今日。 */
    List<Visit> findByOwnerIdAndServiceDateAndStatusNot(UUID ownerId, LocalDate serviceDate, VisitStatus status);

    /** FC1 鎖後重讀：該訂單仍逾時的 IN_PROGRESS 行程。 */
    List<Visit> findByOrderIdAndStatusAndStartedAtLessThanEqualOrderByServiceDateAscSequenceInDayAsc(
            UUID orderId, VisitStatus status, OffsetDateTime startedAtMax);

    /** C7 候選（SD-ORDER CQ-22：唯讀 JOIN orders / users 的排程候選例外）。 */
    @Query(value = """
            SELECT DISTINCT v.order_id FROM visits v JOIN orders o ON o.id = v.order_id
            WHERE v.status = 'PENDING' AND v.service_date <= :maxDate AND o.status IN (:statuses)
              AND (CAST(:orderId AS uuid) IS NULL OR o.id = CAST(:orderId AS uuid))
              AND (CAST(:orderId AS uuid) IS NOT NULL OR NOT EXISTS (SELECT 1 FROM users u
                     WHERE u.id IN (o.owner_id, o.sitter_id) AND u.email LIKE '%@e2e-journey.test'))
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findZombieOrderIds(@Param("maxDate") LocalDate maxDate, @Param("statuses") Collection<String> statuses,
                                  @Param("orderId") UUID orderId, @Param("limit") int limit);

    /** FC1 候選（排程候選例外：唯讀 JOIN orders / users；ix_visits_in_progress）。 */
    @Query(value = """
            SELECT DISTINCT v.order_id FROM visits v JOIN orders o ON o.id = v.order_id
            WHERE v.status = 'IN_PROGRESS' AND v.started_at <= :startedAtMax AND o.status IN ('IN_PROGRESS', 'DISPUTED')
              AND (CAST(:orderId AS uuid) IS NULL OR o.id = CAST(:orderId AS uuid))
              AND (CAST(:orderId AS uuid) IS NOT NULL OR NOT EXISTS (SELECT 1 FROM users u
                     WHERE u.id IN (o.owner_id, o.sitter_id) AND u.email LIKE '%@e2e-journey.test'))
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findInProgressTimeoutOrderIds(@Param("startedAtMax") OffsetDateTime startedAtMax,
                                             @Param("orderId") UUID orderId, @Param("limit") int limit);

    /** FT2 SERVICE_DATE：同 order_item_id 整群組一起平移（避免撞 ux_visits_slot）。 */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @Query(value = "UPDATE visits SET service_date = service_date + :days, updated_at = now() WHERE order_item_id = :orderItemId",
            nativeQuery = true)
    int shiftGroupServiceDate(@Param("orderItemId") UUID orderItemId, @Param("days") int days);

    /** FT1 / FT2：行程所屬訂單的 owner 或 sitter 是否為 {@code @e2e-journey.test} 帳號。 */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM visits v JOIN users u ON u.id IN (v.owner_id, v.sitter_id)
                           WHERE v.id = :visitId AND u.email LIKE '%@e2e-journey.test')
            """, nativeQuery = true)
    boolean isTestAccountVisit(@Param("visitId") UUID visitId);
}
