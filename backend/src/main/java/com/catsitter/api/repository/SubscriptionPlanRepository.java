package com.catsitter.api.repository;

import com.catsitter.api.entity.SubscriptionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, UUID> {
    Optional<SubscriptionPlan> findByName(String name);
    Optional<SubscriptionPlan> findByPlanCode(String planCode);
}
