package com.catsitter.api.repository;

import com.catsitter.api.entity.Pet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PetRepository extends JpaRepository<Pet, UUID> {
  List<Pet> findByClientProfileId(UUID clientProfileId);
}
