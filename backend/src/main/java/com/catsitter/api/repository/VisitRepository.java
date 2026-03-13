package com.catsitter.api.repository;

import com.catsitter.api.entity.Visit;
import com.catsitter.api.entity.enums.VisitStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VisitRepository extends JpaRepository<Visit, UUID> {
  List<Visit> findByOrderId(UUID orderId);
  List<Visit> findByOrderIdAndStatus(UUID orderId, VisitStatus status);
}
