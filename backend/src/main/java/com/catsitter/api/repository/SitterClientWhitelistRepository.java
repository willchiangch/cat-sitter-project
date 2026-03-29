package com.catsitter.api.repository;

import com.catsitter.api.entity.SitterClientWhitelist;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

public interface SitterClientWhitelistRepository extends JpaRepository<SitterClientWhitelist, UUID> {
    Optional<SitterClientWhitelist> findBySitterProfileIdAndClientProfileId(UUID sitterProfileId, UUID clientProfileId);
    List<SitterClientWhitelist> findBySitterProfileId(UUID sitterProfileId);
}
