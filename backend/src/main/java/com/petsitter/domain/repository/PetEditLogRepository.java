package com.petsitter.domain.repository;

import com.petsitter.domain.model.PetEditLog;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
/** 稽核實體：append-only（SD-GLOBAL-SPEC §4.3、NFR-003 SEC-AUDIT-3）。 */
public interface PetEditLogRepository extends AppendOnlyRepository<PetEditLog, UUID>, AppendOnlyInsert<PetEditLog> {
    List<PetEditLog> findByPetIdOrderByCreatedAtDesc(UUID petId);
}
