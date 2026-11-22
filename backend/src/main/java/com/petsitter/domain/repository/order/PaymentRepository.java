package com.petsitter.domain.repository.order;

import com.petsitter.domain.model.order.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findByOrderIdOrderByCreatedAtAscIdAsc(UUID orderId);

    List<Payment> findByOrderIdIn(Collection<UUID> orderIds);

    Optional<Payment> findByModificationRequestId(UUID modificationRequestId);

    /** 已收淨額 = Σ CHARGE VERIFIED − Σ REFUND VERIFIED（baseline §4）。 */
    @Query(value = """
            SELECT COALESCE(SUM(CASE WHEN direction = 'CHARGE' THEN amount ELSE -amount END), 0)
            FROM payments WHERE order_id = :orderId AND status = 'VERIFIED'
            """, nativeQuery = true)
    int netPaid(@Param("orderId") UUID orderId);

    // ------------------------------------------------------------------ 排程候選

    @Query(value = """
            SELECT p.order_id FROM payments p JOIN orders o ON o.id = p.order_id
            WHERE p.status = 'UNPAID' AND p.purpose = 'INITIAL' AND p.remind_at <= :now AND p.reminded_at IS NULL
              AND p.due_at > :now
              AND (CAST(:orderId AS uuid) IS NULL OR o.id = CAST(:orderId AS uuid))
              AND (CAST(:orderId AS uuid) IS NOT NULL OR NOT EXISTS (SELECT 1 FROM users u
                     WHERE u.id IN (o.owner_id, o.sitter_id) AND u.email LIKE '%@e2e-journey.test'))
            ORDER BY p.remind_at LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findReminderCandidates(@Param("now") OffsetDateTime now, @Param("orderId") UUID orderId,
                                      @Param("limit") int limit);

    @Query(value = """
            SELECT p.order_id FROM payments p JOIN orders o ON o.id = p.order_id
            WHERE p.status = 'UNPAID' AND p.purpose = :purpose AND p.due_at <= :now
              AND o.status <> 'DISPUTED'
              AND (CAST(:orderId AS uuid) IS NULL OR o.id = CAST(:orderId AS uuid))
              AND (CAST(:orderId AS uuid) IS NOT NULL OR NOT EXISTS (SELECT 1 FROM users u
                     WHERE u.id IN (o.owner_id, o.sitter_id) AND u.email LIKE '%@e2e-journey.test'))
            ORDER BY p.due_at LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findDueCandidates(@Param("purpose") String purpose, @Param("now") OffsetDateTime now,
                                 @Param("orderId") UUID orderId, @Param("limit") int limit);

    @Query(value = """
            SELECT p.order_id FROM payments p JOIN orders o ON o.id = p.order_id
            WHERE p.status = 'SUBMITTED' AND p.purpose = 'INITIAL' AND p.escalate_at <= :now AND p.escalated_at IS NULL
              AND (CAST(:orderId AS uuid) IS NULL OR o.id = CAST(:orderId AS uuid))
              AND (CAST(:orderId AS uuid) IS NOT NULL OR NOT EXISTS (SELECT 1 FROM users u
                     WHERE u.id IN (o.owner_id, o.sitter_id) AND u.email LIKE '%@e2e-journey.test'))
            ORDER BY p.escalate_at LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findEscalationCandidates(@Param("now") OffsetDateTime now, @Param("orderId") UUID orderId,
                                        @Param("limit") int limit);
}
