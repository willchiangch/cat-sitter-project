package com.petsitter.domain.repository.fulfill;

import com.petsitter.domain.model.fulfill.VisitServiceReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VisitServiceReportRepository extends JpaRepository<VisitServiceReport, UUID> {

    Optional<VisitServiceReport> findByVisitId(UUID visitId);

    @Query("SELECT r.visitId FROM VisitServiceReport r WHERE r.visitId IN :visitIds AND r.status = 'SUBMITTED'")
    List<UUID> findSubmittedVisitIds(@Param("visitIds") Collection<UUID> visitIds);
}
