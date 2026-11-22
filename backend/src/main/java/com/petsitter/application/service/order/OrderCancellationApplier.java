package com.petsitter.application.service.order;

import com.petsitter.application.service.fulfill.VisitCommandService;
import com.petsitter.domain.model.order.ActorRole;
import com.petsitter.domain.model.order.CancelReasonCode;
import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.model.order.Payment;
import com.petsitter.domain.model.order.PaymentStatus;
import com.petsitter.domain.repository.order.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 取消的共同附帶動作（SD-ORDER §3.1.2、baseline CQ-65）：訂單 → CANCELLED；所有 UNPAID / SUBMITTED payment → VOID
 * （submission 不改）；SD-FULFILL {@code cancelPendingVisits}。不寫 order_logs，回傳 {@code transitions[]} 供呼叫端那一列使用。
 */
@Component
@RequiredArgsConstructor
public class OrderCancellationApplier {

    private final PaymentRepository paymentRepository;
    private final VisitCommandService visitCommandService;
    private final OrderLockService orderLockService;
    private final OrderClock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Map<String, Object>> apply(LockedOrder locked, ActorRole cancelledBy, CancelReasonCode reasonCode,
                                           String reason, UUID actorUserId) {
        orderLockService.requireHeld(locked);
        Order order = locked.entity();
        OffsetDateTime now = clock.now();
        List<Map<String, Object>> transitions = new ArrayList<>();
        transitions.add(OrderLogWriter.transition("ORDER", order.getId(), order.getStatus(), OrderStatus.CANCELLED));
        order.setStatus(OrderStatus.CANCELLED);
        order.setCancelledBy(cancelledBy);
        order.setCancelledAt(now);
        order.setCancelReasonCode(reasonCode);
        order.setCancelReason(reason);
        for (Payment p : paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId())) {
            if (p.isOpen()) {
                transitions.add(OrderLogWriter.transition("PAYMENT", p.getId(), p.getStatus(), PaymentStatus.VOID));
                voidPayment(p, now, actorUserId, reasonCode.name(), null);
            }
        }
        for (UUID visitId : visitCommandService.cancelPendingVisits(locked, now)) {
            transitions.add(OrderLogWriter.transition("VISIT", visitId, "PENDING", "CANCELLED"));
        }
        return transitions;
    }

    static void voidPayment(Payment p, OffsetDateTime now, UUID by, String code, String reason) {
        p.setStatus(PaymentStatus.VOID);
        p.setVoidedAt(now);
        p.setVoidedBy(by);
        p.setVoidReasonCode(code);
        p.setVoidReason(reason);
    }
}
