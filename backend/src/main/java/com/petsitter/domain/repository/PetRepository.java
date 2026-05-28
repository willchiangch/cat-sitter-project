package com.petsitter.domain.repository;

import com.petsitter.domain.model.Pet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PetRepository extends JpaRepository<Pet, UUID> {
    List<Pet> findByOwnerIdAndIsDeletedFalse(UUID ownerId);
    
    Optional<Pet> findByIdAndIsDeletedFalse(UUID id);
}
