package com.catsitter.api.repository;

import com.catsitter.api.entity.SitterTrustCircle;
import com.catsitter.api.entity.enums.TrustCircleStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SitterTrustCircleRepository extends JpaRepository<SitterTrustCircle, UUID> {
  List<SitterTrustCircle> findByOwnerSitterIdAndStatus(UUID ownerSitterId, TrustCircleStatus status);
}
