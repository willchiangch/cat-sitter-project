package com.catsitter.api.repository;

import com.catsitter.api.entity.Profile;
import com.catsitter.api.entity.Visit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface VisitRepository extends JpaRepository<Visit, UUID> {
    
    @Query("SELECT v FROM Visit v JOIN v.order o WHERE o.currentSitter = :sitter AND v.visitStartTime >= :start AND v.visitStartTime < :end")
    List<Visit> findBySitterAndDate(
            @Param("sitter") Profile sitter,
            @Param("start") OffsetDateTime start,
            @Param("end") OffsetDateTime end
    );

    List<Visit> findByOrderId(UUID orderId);
    
    List<Visit> findByOrderClientProfileIdOrderByVisitStartTimeDesc(UUID clientProfileId);

    List<Visit> findByOrderCurrentSitterIdAndOrderOrderStatus(UUID sitterId, com.catsitter.api.entity.enums.OrderStatus status);
}
