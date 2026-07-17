package com.petsitter.domain.repository;

import com.petsitter.domain.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByIdAndSitterId(UUID id, UUID sitterId);

    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:key1), hashtext(:key2))", nativeQuery = true)
    void acquireAdvisoryLock(@Param("key1") String key1, @Param("key2") String key2);

    @Query("SELECT o FROM Order o WHERE o.status IN ('IN_PROGRESS', 'CONFIRMED') " +
           "AND NOT EXISTS (SELECT v FROM Visit v WHERE v.order = o AND v.status NOT IN ('DONE', 'CLOSED_BY_SYSTEM')) " +
           "AND NOT EXISTS (SELECT v2 FROM Visit v2 WHERE v2.order = o AND v2.scheduledAt > :threshold)")
    List<Order> findOrdersReadyForAutoComplete(@Param("threshold") OffsetDateTime threshold);

    List<Order> findByStatus(String status);

    List<Order> findByOwnerIdAndStatusNotIn(UUID ownerId, List<String> statuses);

    @Query("SELECT o FROM Order o JOIN FETCH o.owner JOIN FETCH o.sitter WHERE o.owner.id = :ownerId AND o.isDeleted = false ORDER BY o.createdAt DESC")
    List<Order> findByOwnerIdWithParties(@Param("ownerId") UUID ownerId);

    @Query("SELECT o FROM Order o JOIN FETCH o.owner JOIN FETCH o.sitter WHERE o.sitter.id = :sitterId AND o.isDeleted = false ORDER BY o.createdAt DESC")
    List<Order> findBySitterIdWithParties(@Param("sitterId") UUID sitterId);

    @Modifying
    @Query("UPDATE Order o SET o.isDeleted = true WHERE o.owner.email IN :emails OR o.sitter.email IN :emails")
    int softDeleteByPartyEmails(@Param("emails") List<String> emails);

    boolean existsByPaymentIdempotencyKey(String paymentIdempotencyKey);

    @Query("SELECT o FROM Order o JOIN FETCH o.owner WHERE o.sitter.id = :sitterId AND o.status = 'COMPLETED' " +
           "AND o.completedAt >= :from AND o.completedAt < :to ORDER BY o.completedAt DESC")
    List<Order> findCompletedBySitterIdAndCompletedAtBetween(
            @Param("sitterId") UUID sitterId,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    @Query(value = """
        SELECT o.* FROM orders o
        JOIN order_snapshots os ON os.order_id = o.id
        WHERE o.status = 'COMPLETED'
          AND o.media_expiry_warned = false
          AND os.media_retention_days != -1
          AND o.completed_at + ((os.media_retention_days - 3) || ' day')::interval <= :now
          AND EXISTS (
              SELECT 1 FROM service_report_media srm2
              JOIN visit_service_reports sr2 ON srm2.report_id = sr2.id
              JOIN visits v2 ON sr2.visit_id = v2.id
              WHERE v2.order_id = o.id 
                AND srm2.is_purged = false 
                AND srm2.is_deleted = false
          )
        """, nativeQuery = true)
    List<Order> findOrdersPendingMediaExpiryWarning(@Param("now") OffsetDateTime now);

}
