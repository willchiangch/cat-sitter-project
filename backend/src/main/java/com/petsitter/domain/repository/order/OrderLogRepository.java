package com.petsitter.domain.repository.order;

import com.petsitter.domain.model.order.OrderLog;
import com.petsitter.domain.repository.AppendOnlyInsert;
import com.petsitter.domain.repository.AppendOnlyRepository;

import java.util.List;
import java.util.UUID;

/** 稽核實體：append-only（SD-GLOBAL-SPEC §4.3、NFR-003 SEC-AUDIT-3）。 */
public interface OrderLogRepository extends AppendOnlyRepository<OrderLog, UUID>, AppendOnlyInsert<OrderLog> {

    List<OrderLog> findByOrderIdOrderByCreatedAtAscIdAsc(UUID orderId);

    List<OrderLog> findByOrderIdAndActionTypeOrderByCreatedAtAsc(UUID orderId, String actionType);

    boolean existsByOrderIdAndActionType(UUID orderId, String actionType);
}
