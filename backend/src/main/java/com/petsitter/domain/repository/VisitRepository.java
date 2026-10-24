package com.petsitter.domain.repository;

import com.petsitter.domain.model.Visit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface VisitRepository extends JpaRepository<Visit, UUID> {

    List<Visit> findByOrderId(UUID orderId);

    List<Visit> findByStatusAndScheduledAtBefore(String status, OffsetDateTime time);
}