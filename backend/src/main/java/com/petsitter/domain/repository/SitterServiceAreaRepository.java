package com.petsitter.domain.repository;

import com.petsitter.domain.model.SitterServiceArea;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SitterServiceAreaRepository extends JpaRepository<SitterServiceArea, UUID> {
    List<SitterServiceArea> findByProfileId(UUID profileId);
    void deleteByProfileId(UUID profileId);
}
