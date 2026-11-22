package com.petsitter.domain.repository.order;

import com.petsitter.domain.model.order.AdminTask;
import com.petsitter.domain.model.order.AdminTaskStatus;
import com.petsitter.domain.model.order.AdminTaskType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminTaskRepository extends JpaRepository<AdminTask, UUID> {

    List<AdminTask> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

    List<AdminTask> findByOrderIdAndStatus(UUID orderId, AdminTaskStatus status);

    Optional<AdminTask> findByOrderIdAndTypeAndStatus(UUID orderId, AdminTaskType type, AdminTaskStatus status);

    boolean existsByOrderId(UUID orderId);

    @Query("SELECT DISTINCT t.orderId FROM AdminTask t WHERE t.orderId IN :orderIds")
    List<UUID> findOrderIdsWithAnyTask(@Param("orderIds") Collection<UUID> orderIds);

    @Query("""
            SELECT t FROM AdminTask t WHERE t.status = :status AND (:type IS NULL OR t.type = :type)
            ORDER BY t.createdAt ASC, t.id ASC
            """)
    Page<AdminTask> findList(@Param("status") AdminTaskStatus status, @Param("type") AdminTaskType type,
                             Pageable pageable);
}
