package com.catsitter.api.repository;

import com.catsitter.api.entity.Order;
import com.catsitter.api.entity.enums.OrderStatus;
import com.catsitter.api.entity.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {
  List<Order> findByClientProfileId(UUID clientProfileId);
  List<Order> findByCurrentSitterId(UUID sitterId);
  List<Order> findByCurrentSitterIdAndOrderStatus(UUID sitterId, OrderStatus status);
  
  @Query("SELECT SUM(o.totalAmount) FROM Order o WHERE o.currentSitter.id = :sitterId AND o.orderStatus = :status")
  BigDecimal sumTotalAmountBySitterAndStatus(@Param("sitterId") UUID sitterId, @Param("status") OrderStatus status);

  @Query("SELECT COUNT(o) FROM Order o WHERE o.currentSitter.id = :sitterId AND o.orderStatus = :status")
  long countBySitterAndStatus(@Param("sitterId") UUID sitterId, @Param("status") OrderStatus status);

  @Query("SELECT o FROM Order o WHERE o.currentSitter.id = :sitterId ORDER BY o.createdAt DESC")
  List<Order> findRecentBySitter(@Param("sitterId") UUID sitterId, org.springframework.data.domain.Pageable pageable);

  List<Order> findByOrderStatusAndPaymentStatusAndUpdatedAtBefore(
      OrderStatus orderStatus, PaymentStatus paymentStatus, Instant threshold);

  List<Order> findByOrderStatus(OrderStatus orderStatus);
}
