package com.petsitter.domain.repository.order;

import com.petsitter.domain.model.order.OrderLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrderLogRepository extends JpaRepository<OrderLog, UUID> {

    List<OrderLog> findByOrderIdOrderByCreatedAtAscIdAsc(UUID orderId);

    List<OrderLog> findByOrderIdAndActionTypeOrderByCreatedAtAsc(UUID orderId, String actionType);

    boolean existsByOrderIdAndActionType(UUID orderId, String actionType);
}
