package com.petsitter.domain.repository.fulfill;

import com.petsitter.domain.model.fulfill.Visit;
import com.petsitter.domain.model.order.VisitStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** SD-FULFILL 擁有（B 階段過渡；C 階段依 SD-FULFILL 重寫並加 FulfillAggregateArchTest）。 */
public interface VisitRepository extends JpaRepository<Visit, UUID> {

    List<Visit> findByOrderIdOrderByServiceDateAscSequenceInDayAsc(UUID orderId);

    List<Visit> findByOrderIdInOrderByServiceDateAscSequenceInDayAsc(Collection<UUID> orderIds);

    List<Visit> findByOrderIdAndStatus(UUID orderId, VisitStatus status);

    /** 鎖前投影：只取 orderId（不載入實體，SD-FULFILL §2.5 第 1 點）。 */
    @Query("SELECT v.orderId FROM Visit v WHERE v.id = :visitId")
    java.util.Optional<UUID> findOrderIdById(@Param("visitId") UUID visitId);

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
}
