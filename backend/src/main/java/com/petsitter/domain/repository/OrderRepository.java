package com.petsitter.domain.repository;

import com.petsitter.domain.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
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

    boolean existsByPaymentIdempotencyKey(String paymentIdempotencyKey);
}
