package com.petsitter.domain.repository.fulfill;

import com.petsitter.domain.model.fulfill.VisitServiceReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SD-FULFILL 擁有；只准 {@code application.service.fulfill} 依賴（FulfillAggregateArchTest）。 */
public interface VisitServiceReportRepository extends JpaRepository<VisitServiceReport, UUID> {

    Optional<VisitServiceReport> findByVisitId(UUID visitId);

    List<VisitServiceReport> findByVisitIdIn(Collection<UUID> visitIds);

    /** 鎖前投影：日誌狀態（不載入實體）。 */
    @Query("SELECT r.status FROM VisitServiceReport r WHERE r.visitId = :visitId")
    Optional<String> findStatusByVisitId(@Param("visitId") UUID visitId);
}
