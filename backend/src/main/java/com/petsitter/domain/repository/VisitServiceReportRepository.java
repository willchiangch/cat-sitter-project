package com.petsitter.domain.repository;

import com.petsitter.domain.model.VisitServiceReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VisitServiceReportRepository extends JpaRepository<VisitServiceReport, UUID> {
    Optional<VisitServiceReport> findByVisitIdAndIsDeletedFalse(UUID visitId);
    Optional<VisitServiceReport> findByVisitId(UUID visitId);

    /** 已送出（SUBMITTED、未刪除）日誌所屬的行程 ID，給訂單行程摘要標記 reportSubmitted（fix-batch12 U5） */
    @Query("SELECT r.visitId FROM VisitServiceReport r WHERE r.visitId IN :visitIds " +
           "AND r.status = 'SUBMITTED' AND r.isDeleted = false")
    List<UUID> findSubmittedVisitIds(@Param("visitIds") Collection<UUID> visitIds);
}
