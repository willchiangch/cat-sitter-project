package com.petsitter.application.service.order;

import com.petsitter.application.common.idempotency.IdempotencyClaim;
import com.petsitter.application.common.idempotency.IdempotencyContext;
import com.petsitter.application.dto.order.OrderCommandRequests;
import com.petsitter.application.dto.order.OrderCommandResponses;
import com.petsitter.application.exception.OrderException;
import com.petsitter.domain.model.order.ActorRole;
import com.petsitter.domain.model.order.AdminTask;
import com.petsitter.domain.model.order.AdminTaskResolution;
import com.petsitter.domain.model.order.AdminTaskStatus;
import com.petsitter.domain.model.order.AdminTaskType;
import com.petsitter.domain.model.order.ModificationRequest;
import com.petsitter.domain.model.order.ModificationStatus;
import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.model.order.Payment;
import com.petsitter.domain.model.order.PaymentDirection;
import com.petsitter.domain.model.order.PaymentPurpose;
import com.petsitter.domain.model.order.PaymentStatus;
import com.petsitter.domain.model.order.PaymentSubmission;
import com.petsitter.domain.model.order.SubmissionStatus;
import com.petsitter.domain.repository.order.AdminTaskRepository;
import com.petsitter.domain.repository.order.ModificationRequestRepository;
import com.petsitter.domain.repository.order.PaymentSubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** E14 P2 確認、E15 P3 駁回（SD-ORDER §3.2、§6.3 E14 / E15；PRD-007 §4.2、PRD-023 T7 / T10）。 */
@Service
@RequiredArgsConstructor
public class PaymentReviewService {

    private final OrderCommandSupport support;
    private final OrderRequestValidator validator;
    private final PaymentSubmissionRepository submissionRepository;
    private final ModificationRequestRepository modificationRequestRepository;
    private final AdminTaskRepository adminTaskRepository;
    private final ModificationApplier modificationApplier;
    private final OrderLogWriter orderLogWriter;
    private final OrderNotifier notifier;
    private final OrderClock clock;

    public OrderCommandResponses.Verified verify(UUID orderId, UUID paymentId, OrderCommandRequests.Verify req) {
        CurrentActor actor = CurrentActor.fromToken();
        Order order = support.loadOrder(orderId);
        Payment payment = support.loadPayment(order, paymentId);
        ActorRole role = OrderAccess.partyRole(order, actor);
        if (!OrderCommandSupport.isAdminAdjustment(payment) && role != OrderCommandSupport.payee(payment)) {
            throw OrderException.forbidden();
        }
        IdempotencyContext ctx = validator.requireIdempotencyKey();
        UUID submissionId = validator.uuid(req == null ? null : req.submissionId(), "submissionId");
        IdempotencyClaim claim = support.claim(ctx);
        return support.inLockedTx(orderId, locked -> {
            Order o = locked.entity();
            Payment p = support.loadPayment(o, paymentId);
            if (o.getStatus() == OrderStatus.DISPUTED) {
                throw OrderException.stateConflict(o.getStatus());
            }
            PaymentSubmission s = currentSubmission(p, submissionId);
            if (p.getPurpose() == PaymentPurpose.INITIAL && o.getStatus() != OrderStatus.PENDING_PAYMENT) {
                throw OrderException.stateConflict(o.getStatus());
            }
            OffsetDateTime now = clock.now();
            List<Map<String, Object>> transitions = new ArrayList<>();
            s.setStatus(SubmissionStatus.ACCEPTED);
            s.setReviewedAt(now);
            s.setReviewedBy(actor.userId());
            p.setStatus(PaymentStatus.VERIFIED);
            p.setVerifiedAt(now);
            p.setVerifiedBy(actor.userId());
            transitions.add(OrderLogWriter.transition("PAYMENT", p.getId(), PaymentStatus.SUBMITTED, PaymentStatus.VERIFIED));
            transitions.add(OrderLogWriter.transition("SUBMISSION", s.getId(), SubmissionStatus.SUBMITTED, SubmissionStatus.ACCEPTED));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("paymentId", p.getId().toString());
            payload.put("purpose", p.getPurpose().name());
            payload.put("submissionId", s.getId().toString());
            payload.put("amount", p.getAmount());
            OrderCommandResponses.ModificationRef modRef = null;
            if (p.getPurpose() == PaymentPurpose.INITIAL) {
                o.setStatus(OrderStatus.CONFIRMED);
                o.setConfirmedAt(now);
                transitions.add(OrderLogWriter.transition("ORDER", o.getId(), OrderStatus.PENDING_PAYMENT, OrderStatus.CONFIRMED));
                resolveInitialTask(o, AdminTaskResolution.SITTER_VERIFIED, actor.userId(), now, transitions);
                payload.put("confirmedAt", now.toString());
                payload.put("transitions", transitions);
                orderLogWriter.write(locked, "PAYMENT_VERIFIED", new Actor(role, actor.userId()), payload);
                notifier.both(o, "ORDER_ACTIVATED", false, Map.of());
            } else {
                ModificationRequest mod = modificationRequestRepository.findById(p.getModificationRequestId()).orElseThrow();
                ModificationStatus expected = p.getPurpose() == PaymentPurpose.SUPPLEMENT
                        ? ModificationStatus.AWAITING_SUPPLEMENT : ModificationStatus.AWAITING_REFUND;
                if (mod.getStatus() != expected) {
                    throw OrderException.paymentConflict(p.getStatus());
                }
                OrderStatus orderBefore = o.getStatus();
                ModificationApplier.SettleResult r = modificationApplier.settle(locked, mod);
                transitions.add(OrderLogWriter.transition("MODIFICATION", mod.getId(), expected, ModificationStatus.SETTLED));
                r.createdVisitIds().forEach(v -> transitions.add(OrderLogWriter.transition("VISIT", v, null, "PENDING")));
                if (r.orderCancelled()) {
                    transitions.add(OrderLogWriter.transition("ORDER", o.getId(), orderBefore, OrderStatus.CANCELLED));
                }
                payload.put("modificationId", mod.getId().toString());
                payload.put("newTotalAmount", o.getTotalAmount());
                payload.put("transitions", transitions);
                orderLogWriter.write(locked, "PAYMENT_VERIFIED", new Actor(role, actor.userId()), payload);
                notifier.both(o, p.getPurpose() == PaymentPurpose.SUPPLEMENT ? "SUPPLEMENT_VERIFIED" : "REFUND_VERIFIED",
                        false, Map.of());
                modRef = new OrderCommandResponses.ModificationRef(mod.getId(), mod.getStatus().name());
            }
            OrderCommandResponses.Verified resp = new OrderCommandResponses.Verified(p.getId(),
                    PaymentStatus.VERIFIED.name(), o.getStatus().name(), modRef);
            support.complete(claim, 200, resp);
            return resp;
        });
    }

    public OrderCommandResponses.Rejected reject(UUID orderId, UUID paymentId, OrderCommandRequests.Reject req) {
        CurrentActor actor = CurrentActor.fromToken();
        Order order = support.loadOrder(orderId);
        Payment payment = support.loadPayment(order, paymentId);
        ActorRole role = OrderAccess.partyRole(order, actor);
        // REFUND 不可駁回、非 CHARGE 收款方 → 403（先於凍結，PRD-007 AC-2.4）；
        // ADMIN_ADJUSTMENT 跳過方向檢查，鎖內 currentSubmission 回 409（PRD-007 §3 權限表、SD-ORDER §3.2）
        if (!OrderCommandSupport.isAdminAdjustment(payment)
                && (payment.getDirection() != PaymentDirection.CHARGE || role != ActorRole.SITTER)) {
            throw OrderException.forbidden();
        }
        IdempotencyContext ctx = validator.requireIdempotencyKey();
        UUID submissionId = validator.uuid(req == null ? null : req.submissionId(), "submissionId");
        String reason = validator.requiredText(req.reason(), "reason", 200);
        IdempotencyClaim claim = support.claim(ctx);
        return support.inLockedTx(orderId, locked -> {
            Order o = locked.entity();
            Payment p = support.loadPayment(o, paymentId);
            if (o.getStatus() == OrderStatus.DISPUTED) {
                throw OrderException.stateConflict(o.getStatus());
            }
            PaymentSubmission s = currentSubmission(p, submissionId);
            OffsetDateTime now = clock.now();
            List<Map<String, Object>> transitions = new ArrayList<>();
            s.setStatus(SubmissionStatus.REJECTED);
            s.setRejectReason(reason);
            s.setReviewedAt(now);
            s.setReviewedBy(actor.userId());
            p.setStatus(PaymentStatus.UNPAID);
            transitions.add(OrderLogWriter.transition("PAYMENT", p.getId(), PaymentStatus.SUBMITTED, PaymentStatus.UNPAID));
            transitions.add(OrderLogWriter.transition("SUBMISSION", s.getId(), SubmissionStatus.SUBMITTED, SubmissionStatus.REJECTED));
            if (p.getPurpose() == PaymentPurpose.INITIAL) {
                resolveInitialTask(o, AdminTaskResolution.SITTER_REJECTED, actor.userId(), now, transitions);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("paymentId", p.getId().toString());
            payload.put("purpose", p.getPurpose().name());
            payload.put("submissionId", s.getId().toString());
            payload.put("reason", reason);
            payload.put("afterDueAt", p.getDueAt() != null && !now.isBefore(p.getDueAt()));
            payload.put("transitions", transitions);
            orderLogWriter.write(locked, "PAYMENT_REJECTED", Actor.sitter(actor.userId()), payload);
            notifier.owner(o, "PAYMENT_REJECTED", true, Map.of("reason", reason));
            OrderCommandResponses.Rejected resp = new OrderCommandResponses.Rejected(p.getId(), PaymentStatus.UNPAID.name(),
                    s.getId(), SubmissionStatus.REJECTED.name());
            support.complete(claim, 200, resp);
            return resp;
        });
    }

    /** 非 SUBMITTED、ADMIN_ADJUSTMENT、submissionId 非目前那筆 → 409 PAYMENT_STATE_CONFLICT。 */
    private PaymentSubmission currentSubmission(Payment p, UUID submissionId) {
        if (p.getStatus() != PaymentStatus.SUBMITTED || OrderCommandSupport.isAdminAdjustment(p)) {
            throw OrderException.paymentConflict(p.getStatus());
        }
        PaymentSubmission s = submissionRepository.findFirstByPaymentIdAndStatus(p.getId(), SubmissionStatus.SUBMITTED)
                .orElseThrow(() -> OrderException.paymentConflict(p.getStatus()));
        if (!s.getId().equals(submissionId)) {
            throw OrderException.paymentConflict(p.getStatus());
        }
        return s;
    }

    private void resolveInitialTask(Order o, AdminTaskResolution resolution, UUID by, OffsetDateTime now,
                                    List<Map<String, Object>> transitions) {
        adminTaskRepository.findByOrderIdAndTypeAndStatus(o.getId(), AdminTaskType.INITIAL_PAYMENT_UNVERIFIED, AdminTaskStatus.OPEN)
                .ifPresent(t -> resolveTask(t, resolution, by, now, transitions));
    }

    static void resolveTask(AdminTask t, AdminTaskResolution resolution, UUID by, OffsetDateTime now,
                            List<Map<String, Object>> transitions) {
        t.setStatus(AdminTaskStatus.RESOLVED);
        t.setResolution(resolution);
        t.setResolvedBy(by);
        t.setResolvedAt(now);
        transitions.add(OrderLogWriter.transition("ADMIN_TASK", t.getId(), AdminTaskStatus.OPEN, AdminTaskStatus.RESOLVED));
    }
}
