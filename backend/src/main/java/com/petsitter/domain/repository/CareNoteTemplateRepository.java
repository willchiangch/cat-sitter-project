package com.petsitter.domain.repository;

import com.petsitter.domain.model.CareNoteTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CareNoteTemplateRepository extends JpaRepository<CareNoteTemplate, UUID> {
    List<CareNoteTemplate> findBySitterIdOrderByCreatedAtDesc(UUID sitterId);
    
    Optional<CareNoteTemplate> findByIdAndSitterId(UUID id, UUID sitterId);
    
    int countBySitterId(UUID sitterId);
}
