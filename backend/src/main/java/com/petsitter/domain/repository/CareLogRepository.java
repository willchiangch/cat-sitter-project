package com.petsitter.domain.repository;

import com.petsitter.domain.model.CareLog;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
/** 稽核實體：append-only（SD-GLOBAL-SPEC §4.3、NFR-003 SEC-AUDIT-3）。 */
public interface CareLogRepository extends AppendOnlyRepository<CareLog, UUID>, AppendOnlyInsert<CareLog> {
}
