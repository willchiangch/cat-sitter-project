package com.petsitter.domain.repository.order;

import com.petsitter.domain.model.order.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    List<OrderItem> findByOrderIdOrderByCreatedAtAscIdAsc(UUID orderId);

    List<OrderItem> findByOrderIdIn(Collection<UUID> orderIds);
}
