package com.petsitter.domain.repository.order;

import com.petsitter.domain.model.order.ModificationRequest;
import com.petsitter.domain.model.order.ModificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ModificationRequestRepository extends JpaRepository<ModificationRequest, UUID> {

    List<ModificationRequest> findByOrderIdOrderByCreatedAtDescIdDesc(UUID orderId);

    Optional<ModificationRequest> findFirstByOrderIdAndStatusIn(UUID orderId, Collection<ModificationStatus> statuses);

    boolean existsByOrderIdAndStatusIn(UUID orderId, Collection<ModificationStatus> statuses);

    List<ModificationRequest> findByOrderIdAndStatusOrderByEndedAtAsc(UUID orderId, ModificationStatus status);

    List<ModificationRequest> findByOrderIdInAndStatusIn(Collection<UUID> orderIds, Collection<ModificationStatus> statuses);

    @Query(value = """
            SELECT m.order_id FROM modification_requests m JOIN orders o ON o.id = m.order_id
            WHERE m.status IN ('REQUESTED','QUOTED') AND m.expires_at <= :now AND o.status <> 'DISPUTED'
              AND (CAST(:orderId AS uuid) IS NULL OR o.id = CAST(:orderId AS uuid))
              AND (CAST(:orderId AS uuid) IS NOT NULL OR NOT EXISTS (SELECT 1 FROM users u
                     WHERE u.id IN (o.owner_id, o.sitter_id) AND u.email LIKE '%@e2e-journey.test'))
            ORDER BY m.expires_at LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findExpiryCandidates(@Param("now") OffsetDateTime now, @Param("orderId") UUID orderId,
                                    @Param("limit") int limit);
}
