package com.petsitter.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.petsitter.domain.model.CareNote;

import java.util.UUID;

@Repository
public interface AdvisoryLockRepository extends JpaRepository<CareNote, UUID> {
    
    @Query(value = "SELECT pg_advisory_xact_lock(:lockId)", nativeQuery = true)
    void acquireXactLock(@Param("lockId") long lockId);
}
