package com.petsitter.domain.repository;

import com.petsitter.domain.model.VisitServiceReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface VisitServiceReportRepository extends JpaRepository<VisitServiceReport, UUID> {
    Optional<VisitServiceReport> findByVisitIdAndIsDeletedFalse(UUID visitId);
    Optional<VisitServiceReport> findByVisitId(UUID visitId);
}
