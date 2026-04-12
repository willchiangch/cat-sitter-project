package com.catsitter.api.repository;

import com.catsitter.api.entity.SubscriptionChangeLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SubscriptionChangeLogRepository extends JpaRepository<SubscriptionChangeLog, UUID> {
    List<SubscriptionChangeLog> findBySitterProfileIdOrderByCreatedAtDesc(UUID sitterProfileId);
}
