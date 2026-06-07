package com.petsitter.domain.repository;

import com.petsitter.domain.model.KycRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KycRecordRepository extends JpaRepository<KycRecord, UUID> {
    boolean existsBySitterIdAndStatus(UUID sitterId, String status);
    
    Page<KycRecord> findByStatus(String status, Pageable pageable);
    
    long countBySitterIdAndCreatedAtAfter(UUID sitterId, OffsetDateTime since);

    Optional<KycRecord> findFirstBySitterIdOrderByCreatedAtDesc(UUID sitterId);

    @Query(value = "SELECT k, u FROM KycRecord k JOIN User u ON u.id = k.sitterId WHERE k.status = 'PENDING'",
           countQuery = "SELECT count(k) FROM KycRecord k WHERE k.status = 'PENDING'")
    Page<Object[]> findPendingWithUser(Pageable pageable);
}
