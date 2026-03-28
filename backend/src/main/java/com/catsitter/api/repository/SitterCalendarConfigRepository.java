package com.catsitter.api.repository;

import com.catsitter.api.entity.SitterCalendarConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface SitterCalendarConfigRepository extends JpaRepository<SitterCalendarConfig, UUID> {
    Optional<SitterCalendarConfig> findBySitterProfileId(UUID sitterProfileId);
    Optional<SitterCalendarConfig> findByIcalToken(String icalToken);
}
