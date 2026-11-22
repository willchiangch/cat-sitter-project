package com.petsitter.domain.repository.order;

import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** 訂單聚合 Repository：只准 {@code application.service.order} 依賴（{@code OrderAggregateArchTest}）。 */
public interface OrderRepository extends JpaRepository<Order, UUID> {

    /** 月額度：當月（Asia/Taipei 自然月，由呼叫端換算成 UTC 區間）進入 CONFIRMED 的筆數（ix_orders_sitter_confirmed）。 */
    @Query("SELECT COUNT(o) FROM Order o WHERE o.sitterId = :sitterId AND o.confirmedAt >= :from AND o.confirmedAt < :to")
    long countConfirmedBetween(@Param("sitterId") UUID sitterId, @Param("from") OffsetDateTime from,
                               @Param("to") OffsetDateTime to);

    /** 熟客（ix_orders_owner_sitter_completed）。 */
    @Query("SELECT COUNT(o) > 0 FROM Order o WHERE o.ownerId = :ownerId AND o.sitterId = :sitterId "
            + "AND o.status = com.petsitter.domain.model.order.OrderStatus.COMPLETED")
    boolean existsCompletedBetween(@Param("ownerId") UUID ownerId, @Param("sitterId") UUID sitterId);

    boolean existsBySitterIdAndOwnerId(UUID sitterId, UUID ownerId);

    @Query("SELECT COUNT(o) > 0 FROM Order o WHERE (o.ownerId = :userId OR o.sitterId = :userId) AND o.status NOT IN :terminal")
    boolean existsActiveForParty(@Param("userId") UUID userId, @Param("terminal") Collection<OrderStatus> terminal);

    @Query("SELECT COUNT(o) > 0 FROM Order o WHERE o.sitterId = :sitterId AND o.ownerId = :ownerId AND o.status NOT IN :terminal")
    boolean existsActiveBetween(@Param("sitterId") UUID sitterId, @Param("ownerId") UUID ownerId,
                                @Param("terminal") Collection<OrderStatus> terminal);

    @Query(value = """
            SELECT EXISTS (
              SELECT 1 FROM order_item_pets p
              JOIN order_items i ON i.id = p.order_item_id
              JOIN orders o ON o.id = i.order_id
              WHERE p.pet_id = :petId AND o.status NOT IN ('COMPLETED', 'CANCELLED'))
            """, nativeQuery = true)
    boolean existsActiveWithPet(@Param("petId") UUID petId);

    Page<Order> findByOwnerIdAndStatusIn(UUID ownerId, Collection<OrderStatus> statuses, Pageable pageable);

    Page<Order> findBySitterIdAndStatusIn(UUID sitterId, Collection<OrderStatus> statuses, Pageable pageable);

    /** 歷史分頁（終態時間新到舊；可依終態時間區間篩選）。 */
    @Query(value = """
            SELECT o FROM Order o
            WHERE ((:asOwner = true AND o.ownerId = :userId) OR (:asOwner = false AND o.sitterId = :userId))
              AND o.status IN :statuses
              AND (:from IS NULL OR COALESCE(o.completedAt, o.cancelledAt) >= :from)
              AND (:to IS NULL OR COALESCE(o.completedAt, o.cancelledAt) < :to)
            ORDER BY COALESCE(o.completedAt, o.cancelledAt) DESC, o.id DESC
            """,
            countQuery = """
            SELECT COUNT(o) FROM Order o
            WHERE ((:asOwner = true AND o.ownerId = :userId) OR (:asOwner = false AND o.sitterId = :userId))
              AND o.status IN :statuses
              AND (:from IS NULL OR COALESCE(o.completedAt, o.cancelledAt) >= :from)
              AND (:to IS NULL OR COALESCE(o.completedAt, o.cancelledAt) < :to)
            """)
    Page<Order> findHistory(@Param("userId") UUID userId, @Param("asOwner") boolean asOwner,
                            @Param("statuses") Collection<OrderStatus> statuses,
                            @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to, Pageable pageable);

    /** E30：該月進入終態（completed_at / cancelled_at）的保母訂單（ix_orders_sitter_terminal）。 */
    @Query("""
            SELECT o FROM Order o WHERE o.sitterId = :sitterId
              AND o.status IN (com.petsitter.domain.model.order.OrderStatus.COMPLETED,
                               com.petsitter.domain.model.order.OrderStatus.CANCELLED)
              AND COALESCE(o.completedAt, o.cancelledAt) >= :from AND COALESCE(o.completedAt, o.cancelledAt) < :to
            ORDER BY COALESCE(o.completedAt, o.cancelledAt) DESC, o.id DESC
            """)
    List<Order> findTerminalForSitterBetween(@Param("sitterId") UUID sitterId, @Param("from") OffsetDateTime from,
                                             @Param("to") OffsetDateTime to);

    // ------------------------------------------------------------------ 排程候選（唯讀，LIMIT；排除測試帳號）

    @Query(value = """
            SELECT o.id FROM orders o
            WHERE o.status = 'PENDING' AND o.review_expires_at <= :now
              AND (CAST(:orderId AS uuid) IS NULL OR o.id = CAST(:orderId AS uuid))
              AND (CAST(:orderId AS uuid) IS NOT NULL OR NOT EXISTS (SELECT 1 FROM users u
                     WHERE u.id IN (o.owner_id, o.sitter_id) AND u.email LIKE '%@e2e-journey.test'))
            ORDER BY o.review_expires_at LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findReviewExpiryCandidates(@Param("now") OffsetDateTime now, @Param("orderId") UUID orderId,
                                          @Param("limit") int limit);

    @Query(value = """
            SELECT o.id FROM orders o
            WHERE o.status = 'IN_PROGRESS' AND o.completion_basis_at IS NOT NULL AND o.completion_basis_at <= :threshold
              AND NOT EXISTS (SELECT 1 FROM modification_requests m WHERE m.order_id = o.id
                     AND m.status IN ('REQUESTED','QUOTED','AWAITING_SUPPLEMENT','AWAITING_REFUND'))
              AND (CAST(:orderId AS uuid) IS NULL OR o.id = CAST(:orderId AS uuid))
              AND (CAST(:orderId AS uuid) IS NOT NULL OR NOT EXISTS (SELECT 1 FROM users u
                     WHERE u.id IN (o.owner_id, o.sitter_id) AND u.email LIKE '%@e2e-journey.test'))
            ORDER BY o.completion_basis_at LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findAutoCompleteCandidates(@Param("threshold") OffsetDateTime threshold, @Param("orderId") UUID orderId,
                                          @Param("limit") int limit);

    /** owner 或 sitter 為 @e2e-journey.test 帳號（§6.3 C1–C10 orderId 參數、§10.4 test-data）。 */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM orders o JOIN users u ON u.id IN (o.owner_id, o.sitter_id)
                           WHERE o.id = :orderId AND u.email LIKE '%@e2e-journey.test')
            """, nativeQuery = true)
    boolean isTestAccountOrder(@Param("orderId") UUID orderId);

    /** M32：PRD-013 媒體到期提醒候選（只讀 orders / order_snapshots）。 */
    @Query(value = """
            SELECT o.id FROM orders o JOIN order_snapshots s ON s.order_id = o.id
            WHERE o.status = 'COMPLETED' AND o.media_expiry_warned = false AND s.media_retention_days <> -1
              AND o.completed_at + make_interval(days => s.media_retention_days) >= :from
              AND o.completed_at + make_interval(days => s.media_retention_days) < :to
            ORDER BY o.completed_at LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findCompletedPendingMediaExpiryWarning(@Param("from") OffsetDateTime from,
                                                     @Param("to") OffsetDateTime to, @Param("limit") int limit);

    @Modifying
    @Query(value = "UPDATE orders SET media_expiry_warned = true WHERE id = :orderId", nativeQuery = true)
    int markMediaExpiryWarned(@Param("orderId") UUID orderId);
}
