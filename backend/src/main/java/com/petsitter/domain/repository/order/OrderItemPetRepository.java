package com.petsitter.domain.repository.order;

import com.petsitter.domain.model.order.OrderItemPet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface OrderItemPetRepository extends JpaRepository<OrderItemPet, OrderItemPet.Key> {

    @Query("SELECT p FROM OrderItemPet p WHERE p.id.orderItemId IN :itemIds")
    List<OrderItemPet> findByOrderItemIds(@Param("itemIds") Collection<java.util.UUID> itemIds);
}
