package com.petsitter.domain.repository.order;

import com.petsitter.domain.model.order.OrderSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderSnapshotRepository extends JpaRepository<OrderSnapshot, UUID> {

    Optional<OrderSnapshot> findByOrderId(UUID orderId);

    /** 保母已結案、媒體未刪除且保留期限非永久的訂單快照（PRD-013 升級展延）。 */
    @Query(value = """
            SELECT s.* FROM order_snapshots s JOIN orders o ON o.id = s.order_id
            WHERE o.sitter_id = :sitterId AND s.media_retention_days <> -1
            """, nativeQuery = true)
    List<OrderSnapshot> findExtendableForSitter(@Param("sitterId") UUID sitterId);
}
