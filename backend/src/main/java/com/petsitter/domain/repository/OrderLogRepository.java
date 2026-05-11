package com.petsitter.domain.repository;

import com.petsitter.domain.model.OrderLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface OrderLogRepository extends JpaRepository<OrderLog, UUID> {
    long countByOrderIdAndActionType(UUID orderId, String actionType);
}
