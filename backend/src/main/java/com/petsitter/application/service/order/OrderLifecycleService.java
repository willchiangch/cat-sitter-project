package com.petsitter.application.service.order;

import com.petsitter.application.service.fulfill.VisitExitCause;
import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 給 SD-FULFILL 的 O9 / 結案基準入口（SD-ORDER §2.3.1、§2.4.1）。皆 {@code MANDATORY} 且須已持有訂單鎖。
 */
@Service
@RequiredArgsConstructor
public class OrderLifecycleService {

    private final OrderLockService orderLockService;
    private final OrderLogWriter orderLogWriter;
    private final OrderAggregateSupport support;

    /** 訂單 CONFIRMED → 同交易 O9 並寫 {@code ORDER_STARTED}；DISPUTED / IN_PROGRESS 不變（CQ-28、CQ-56、CQ-57）。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public OrderStatus onVisitLeftPending(LockedOrder locked, UUID visitId, VisitExitCause cause, Actor actor) {
        orderLockService.requireHeld(locked);
        Order order = locked.entity();
        if (order.getStatus() == OrderStatus.CONFIRMED) {
            order.setStatus(OrderStatus.IN_PROGRESS);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("visitId", visitId.toString());
            payload.put("cause", cause.name());
            payload.put("transitions", List.of(OrderLogWriter.transition("ORDER", order.getId(),
                    OrderStatus.CONFIRMED, OrderStatus.IN_PROGRESS)));
            orderLogWriter.write(locked, "ORDER_STARTED", actor, payload);
        }
        return order.getStatus();
    }

    /** 行程轉 DONE / CLOSED_BY_SYSTEM 後重算 {@code completion_basis_at}（§3.1.3）。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onVisitFinished(LockedOrder locked) {
        orderLockService.requireHeld(locked);
        support.recomputeCompletionBasis(locked.entity());
    }
}
