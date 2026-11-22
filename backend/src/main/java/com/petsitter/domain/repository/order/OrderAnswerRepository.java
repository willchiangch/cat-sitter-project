package com.petsitter.domain.repository.order;

import com.petsitter.domain.model.order.OrderAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrderAnswerRepository extends JpaRepository<OrderAnswer, UUID> {
    List<OrderAnswer> findByOrderIdOrderBySortOrderAsc(UUID orderId);
}
