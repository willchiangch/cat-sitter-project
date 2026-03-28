package com.catsitter.api.repository;

import com.catsitter.api.entity.VisitMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface VisitMediaRepository extends JpaRepository<VisitMedia, UUID> {
    List<VisitMedia> findByVisitId(UUID visitId);
    
    /**
     * Finds media records that are older than the specified date and haven't been marked as deleted.
     */
    List<VisitMedia> findByCreatedAtBeforeAndIsDeletedFalse(Instant expiryDate);
}
