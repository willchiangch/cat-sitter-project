package com.catsitter.api.repository;

import com.catsitter.api.entity.Order;
import com.catsitter.api.entity.enums.OrderStatus;
import com.catsitter.api.entity.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {
  List<Order> findByClientProfileId(UUID clientProfileId);
  List<Order> findByCurrentSitterIdAndOrderStatus(UUID sitterId, OrderStatus status);
  
  List<Order> findByOrderStatusAndPaymentStatusAndUpdatedAtBefore(
      OrderStatus orderStatus, PaymentStatus paymentStatus, Instant threshold);

  List<Order> findByOrderStatus(OrderStatus orderStatus);
}
