package com.petsitter.domain.repository;

import com.petsitter.domain.model.ModificationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
import java.util.Optional;

import java.util.List;

public interface ModificationRequestRepository extends JpaRepository<ModificationRequest, UUID> {
    Optional<ModificationRequest> findByOrderIdAndStatus(UUID orderId, String status);
    List<ModificationRequest> findByOrderIdOrderByCreatedAtDesc(UUID orderId);
}