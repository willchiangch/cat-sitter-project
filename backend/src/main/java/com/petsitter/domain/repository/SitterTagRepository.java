package com.petsitter.domain.repository;

import com.petsitter.domain.model.SitterTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SitterTagRepository extends JpaRepository<SitterTag, UUID> {
    List<SitterTag> findByProfileId(UUID profileId);
    void deleteByProfileId(UUID profileId);
}
