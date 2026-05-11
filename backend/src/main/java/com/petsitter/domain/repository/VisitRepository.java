package com.petsitter.domain.repository;

import com.petsitter.domain.model.Visit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface VisitRepository extends JpaRepository<Visit, UUID> {

    List<Visit> findByOrderId(UUID orderId);

    @Query("SELECT COUNT(v) FROM Visit v JOIN v.order o " +
           "WHERE o.sitter.id = :sitterId AND v.scheduledAt = :date " +
           "AND o.status IN ('CONFIRMED', 'PENDING_PAYMENT', 'IN_PROGRESS', 'COMPLETED') " +
           "AND o.isDeleted = false")
    long countBookedVisitsBySitterIdAndDate(@Param("sitterId") UUID sitterId, @Param("date") OffsetDateTime date);

    List<Visit> findByStatusAndScheduledAtBefore(String status, OffsetDateTime time);
}
