package com.petsitter.domain.repository.order;

import com.petsitter.domain.model.order.OrderPlanSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OrderPlanSnapshotRepository extends JpaRepository<OrderPlanSnapshot, OrderPlanSnapshot.Key> {

    @Query("SELECT s FROM OrderPlanSnapshot s WHERE s.id.orderId = :orderId")
    List<OrderPlanSnapshot> findByOrderId(@Param("orderId") UUID orderId);

    @Query("SELECT s FROM OrderPlanSnapshot s WHERE s.id.orderId IN :orderIds")
    List<OrderPlanSnapshot> findByOrderIds(@Param("orderIds") Collection<UUID> orderIds);
}
