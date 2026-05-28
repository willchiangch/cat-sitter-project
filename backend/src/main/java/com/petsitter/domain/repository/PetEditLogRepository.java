package com.petsitter.domain.repository;

import com.petsitter.domain.model.PetEditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PetEditLogRepository extends JpaRepository<PetEditLog, UUID> {
    List<PetEditLog> findByPetIdOrderByCreatedAtDesc(UUID petId);
}
