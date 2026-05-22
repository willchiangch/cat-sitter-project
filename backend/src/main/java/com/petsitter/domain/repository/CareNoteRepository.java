package com.petsitter.domain.repository;

import com.petsitter.domain.model.CareNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CareNoteRepository extends JpaRepository<CareNote, UUID> {
    Optional<CareNote> findBySitterIdAndOwnerId(UUID sitterId, UUID ownerId);
}
