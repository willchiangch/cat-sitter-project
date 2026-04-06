package com.catsitter.api.repository;

import com.catsitter.api.entity.SitterClientBlacklist;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SitterClientBlacklistRepository extends JpaRepository<SitterClientBlacklist, UUID> {
    List<SitterClientBlacklist> findBySitterProfileId(UUID sitterProfileId);
    Optional<SitterClientBlacklist> findBySitterProfileIdAndClientProfileId(UUID sitterProfileId, UUID clientProfileId);
}
