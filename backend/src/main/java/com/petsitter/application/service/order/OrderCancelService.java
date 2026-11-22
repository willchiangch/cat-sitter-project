package com.petsitter.application.service.order;

import com.petsitter.application.common.idempotency.IdempotencyClaim;
import com.petsitter.application.common.idempotency.IdempotencyContext;
import com.petsitter.application.dto.order.OrderCommandRequests;
import com.petsitter.application.dto.order.OrderCommandResponses;
import com.petsitter.application.exception.OrderErrorCode;
import com.petsitter.application.exception.OrderException;
import com.petsitter.application.port.UserDisplayQuery;
import com.petsitter.domain.model.order.ActorRole;
import com.petsitter.domain.model.order.CancelReasonCode;
import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.model.order.Payment;
import com.petsitter.domain.model.order.PaymentPurpose;
import com.petsitter.domain.model.order.PaymentStatus;
import com.petsitter.domain.repository.order.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 付款前取消類轉移（SD-ORDER §3.1.1 O3 / O6 / O7；§6.3 E7–E9）：不檢查期限、鎖定、額度（CQ-61）。
 */
@Service
@RequiredArgsConstructor
public class OrderCancelService {

    private static final Set<String> ORDER_STATUSES = Set.of(
            "PENDING", "PENDING_PAYMENT", "CONFIRMED", "IN_PROGRESS", "DISPUTED", "COMPLETED", "CANCELLED");

    private final OrderCommandSupport support;
    private final OrderRequestValidator validator;
    private final PaymentRepository paymentRepository;
    private final OrderCancellationApplier cancellationApplier;
    private final OrderLogWriter orderLogWriter;
    private final OrderNotifier notifier;
    private final UserDisplayQuery userDisplayQuery;

    /** E7 O3（SITTER 婉拒）。 */
    public OrderCommandResponses.Cancelled decline(UUID orderId, OrderCommandRequests.Reason req) {
        CurrentActor actor = CurrentActor.fromToken();
        Order order = support.loadOrder(orderId);
        OrderAccess.requireSitter(order, actor);
        IdempotencyContext ctx = validator.requireIdempotencyKey();
        String reason = validator.requiredText(req == null ? null : req.reason(), "reason", 200);
        IdempotencyClaim claim = support.claim(ctx);
        return support.inLockedTx(orderId, locked -> {
            Order o = locked.entity();
            if (o.getStatus() != OrderStatus.PENDING) {
                throw OrderException.stateConflict(o.getStatus());
            }
            List<Map<String, Object>> transitions = cancellationApplier.apply(locked, ActorRole.SITTER,
                    CancelReasonCode.SITTER_DECLINED, reason, actor.userId());
            orderLogWriter.write(locked, "ORDER_REJECTED", Actor.sitter(actor.userId()),
                    Map.of("reason", reason, "transitions", transitions));
            String ownerName = userDisplayQuery.ownerDisplayNames(Set.of(o.getOwnerId())).get(o.getOwnerId());
            notifier.owner(o, "BOOKING_DECLINED", false, Map.of("reason", reason));
            Map<String, Object> self = new HashMap<>();
            self.put("ownerName", ownerName);
            notifier.sitter(o, "BOOKING_DECLINED_SELF", false, self);
            return done(claim, o, "SITTER");
        });
    }

    /** E8 O3（OWNER 撤回）/ O6。 */
    public OrderCommandResponses.Cancelled withdraw(UUID orderId, OrderCommandRequests.Withdraw req) {
        CurrentActor actor = CurrentActor.fromToken();
        Order order = support.loadOrder(orderId);
        OrderAccess.requireOwner(order, actor);
        IdempotencyContext ctx = validator.requireIdempotencyKey();
        String expected = req == null ? null : req.expectedStatus();
        if (expected == null || !ORDER_STATUSES.contains(expected)) {
            throw OrderException.validation("expectedStatus");
        }
        String reason = validator.optionalText(req.reason(), "reason", 200);
        IdempotencyClaim claim = support.claim(ctx);
        return support.inLockedTx(orderId, locked -> {
            Order o = locked.entity();
            OrderStatus from = o.getStatus();
            if ((from != OrderStatus.PENDING && from != OrderStatus.PENDING_PAYMENT) || !from.name().equals(expected)) {
                throw OrderException.stateConflict(from);
            }
            if (from == OrderStatus.PENDING_PAYMENT) {
                requireInitialUnpaid(o);
            }
            List<Map<String, Object>> transitions = cancellationApplier.apply(locked, ActorRole.OWNER,
                    CancelReasonCode.OWNER_WITHDRAWN, reason, actor.userId());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("fromStatus", from.name());
            payload.put("reason", reason);
            payload.put("transitions", transitions);
            orderLogWriter.write(locked, "ORDER_WITHDRAWN", Actor.owner(actor.userId()), payload);
            Map<String, Object> params = new HashMap<>();
            params.put("firstServiceDate", o.getFirstServiceDate().toString());
            params.put("reason", reason);
            notifier.sitter(o, "BOOKING_WITHDRAWN", false, params);
            notifier.owner(o, "BOOKING_WITHDRAWN_SELF", false, Map.of());
            return done(claim, o, "OWNER");
        });
    }

    /** E9 O7（付款前保母取消）。 */
    public OrderCommandResponses.Cancelled sitterCancel(UUID orderId, OrderCommandRequests.Reason req) {
        CurrentActor actor = CurrentActor.fromToken();
        Order order = support.loadOrder(orderId);
        OrderAccess.requireSitter(order, actor);
        IdempotencyContext ctx = validator.requireIdempotencyKey();
        String reason = validator.requiredText(req == null ? null : req.reason(), "reason", 200);
        IdempotencyClaim claim = support.claim(ctx);
        return support.inLockedTx(orderId, locked -> {
            Order o = locked.entity();
            if (o.getStatus() != OrderStatus.PENDING_PAYMENT) {
                throw OrderException.stateConflict(o.getStatus());
            }
            Payment initial = requireInitialUnpaid(o);
            List<Map<String, Object>> transitions = cancellationApplier.apply(locked, ActorRole.SITTER,
                    CancelReasonCode.SITTER_CANCELLED, reason, actor.userId());
            orderLogWriter.write(locked, "ORDER_CANCELLED_BY_SITTER", Actor.sitter(actor.userId()),
                    Map.of("reason", reason, "paymentId", initial.getId().toString(), "transitions", transitions));
            notifier.owner(o, "SITTER_CANCELLED", false, Map.of("reason", reason));
            notifier.sitter(o, "SITTER_CANCELLED_SELF", false, Map.of());
            return done(claim, o, "SITTER");
        });
    }

    private Payment requireInitialUnpaid(Order o) {
        Optional<Payment> initial = paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(o.getId()).stream()
                .filter(p -> p.getPurpose() == PaymentPurpose.INITIAL).findFirst();
        if (initial.isPresent() && initial.get().getStatus() == PaymentStatus.SUBMITTED) {
            throw new OrderException(OrderErrorCode.PAYMENT_ALREADY_SUBMITTED);
        }
        return initial.orElseThrow(() -> OrderException.stateConflict(o.getStatus()));
    }

    private OrderCommandResponses.Cancelled done(IdempotencyClaim claim, Order o, String by) {
        OrderCommandResponses.Cancelled resp = new OrderCommandResponses.Cancelled(o.getId(), OrderStatus.CANCELLED.name(), by);
        support.complete(claim, 200, resp);
        return resp;
    }
}
