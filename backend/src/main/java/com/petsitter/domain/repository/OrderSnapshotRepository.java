package com.petsitter.domain.repository;

import com.petsitter.domain.model.OrderSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderSnapshotRepository extends JpaRepository<OrderSnapshot, UUID> {
    Optional<OrderSnapshot> findByOrderId(UUID orderId);

    @org.springframework.data.jpa.repository.Query("SELECT os FROM OrderSnapshot os WHERE os.order.sitter.id = :sitterId AND os.order.status = 'COMPLETED'")
    java.util.List<OrderSnapshot> findActiveSnapshotsForUpgrade(@org.springframework.data.repository.query.Param("sitterId") UUID sitterId);

}