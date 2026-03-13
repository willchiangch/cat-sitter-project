package com.catsitter.api.repository;

import com.catsitter.api.entity.OrderAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrderAnswerRepository extends JpaRepository<OrderAnswer, UUID> {
  List<OrderAnswer> findByOrderId(UUID orderId);
}
