package com.petsitter.domain.repository;

import com.petsitter.domain.model.ReferralLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ReferralLogRepository extends JpaRepository<ReferralLog, UUID> {
}
