package com.petsitter.domain.repository;

import com.petsitter.domain.model.CareMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CareMediaRepository extends JpaRepository<CareMedia, UUID> {
    List<CareMedia> findBySitterIdAndOwnerIdOrderByCreatedAtDesc(UUID sitterId, UUID ownerId);
    
    Optional<CareMedia> findByIdAndSitterId(UUID id, UUID sitterId);
    
    int countBySitterIdAndOwnerId(UUID sitterId, UUID ownerId);
}
