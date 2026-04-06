package com.catsitter.api.repository;

import com.catsitter.api.entity.Profile;
import com.catsitter.api.entity.SitterSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SitterSubscriptionRepository extends JpaRepository<SitterSubscription, UUID> {
    Optional<SitterSubscription> findBySitterProfileAndStatus(Profile profile, String status);
    List<SitterSubscription> findByEndDateBetweenAndStatus(LocalDate start, LocalDate end, String status);
    Optional<SitterSubscription> findTopBySitterProfileIdOrderByCreatedAtDesc(UUID sitterProfileId);
    Optional<SitterSubscription> findBySitterProfileIdAndStatus(UUID sitterProfileId, String status);
}
