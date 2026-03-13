package com.catsitter.api.repository;

import com.catsitter.api.entity.OrderActionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrderActionLogRepository extends JpaRepository<OrderActionLog, UUID> {
  List<OrderActionLog> findByOrderIdOrderByCreatedAtAsc(UUID orderId);
}
