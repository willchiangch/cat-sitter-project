package com.petsitter.application.service.order;

import com.petsitter.application.common.idempotency.IdempotencyClaim;
import com.petsitter.application.common.idempotency.IdempotencyContext;
import com.petsitter.application.dto.order.ModificationRequests;
import com.petsitter.application.dto.order.ModificationResponses;
import com.petsitter.application.exception.OrderErrorCode;
import com.petsitter.application.exception.OrderException;
import com.petsitter.application.service.fulfill.VisitQueryService;
import com.petsitter.application.service.fulfill.VisitView;
import com.petsitter.domain.model.order.ActorRole;
import com.petsitter.domain.model.order.AdminTask;
import com.petsitter.domain.model.order.AdminTaskStatus;
import com.petsitter.domain.model.order.AdminTaskType;
import com.petsitter.domain.model.order.ModificationRequest;
import com.petsitter.domain.model.order.ModificationStatus;
import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.model.order.Payment;
import com.petsitter.domain.model.order.PaymentStatus;
import com.petsitter.domain.model.order.PaymentSubmission;
import com.petsitter.domain.model.order.SubmissionStatus;
import com.petsitter.domain.model.order.VisitStatus;
import com.petsitter.domain.repository.order.AdminTaskRepository;
import com.petsitter.domain.repository.order.ModificationRequestRepository;
import com.petsitter.domain.repository.order.PaymentRepository;
import com.petsitter.domain.repository.order.PaymentSubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * E24 O14 款項爭議、E25 O11 確認結案、E26 O13 回報問題（SD-ORDER §3.1.1、§6.3；PRD-024、PRD-009）。
 * 自動結案 O12 由 C8 處理（{@link #autoCompleteInTx}）。
 */
@Service
@RequiredArgsConstructor
public class OrderCompletionService {

    private final OrderCommandSupport support;
    private final OrderRequestValidator validator;
    private final ModificationRequestRepository modificationRequestRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentSubmissionRepository submissionRepository;
    private final AdminTaskRepository adminTaskRepository;
    private final VisitQueryService visitQueryService;
    private final OrderLogWriter orderLogWriter;
    private final OrderNotifier notifier;
    private final OrderSettings settings;
    private final OrderClock clock;

    // ------------------------------------------------------------------ E24

    public ModificationResponses.Disputed paymentDispute(UUID orderId, ModificationRequests.Description req) {
        CurrentActor actor = CurrentActor.fromToken();
        Order order = support.loadOrder(orderId);
        ActorRole role = OrderAccess.partyRole(order, actor);
        IdempotencyContext ctx = validator.requireIdempotencyKey();
        String description = validator.requiredText(req == null ? null : req.description(), "description", 500);
        IdempotencyClaim claim = support.claim(ctx);
        return support.inLockedTx(orderId, locked -> {
            Order o = locked.entity();
            OffsetDateTime now = clock.now();
            if (o.getStatus() != OrderStatus.CONFIRMED && o.getStatus() != OrderStatus.IN_PROGRESS) {
                throw OrderException.stateConflict(o.getStatus());
            }
            ModificationRequest m = modificationRequestRepository.findFirstByOrderIdAndStatusIn(o.getId(),
                            List.of(ModificationStatus.AWAITING_SUPPLEMENT, ModificationStatus.AWAITING_REFUND))
                    .orElseThrow(() -> new OrderException(OrderErrorCode.MODIFICATION_STATE_CONFLICT,
                            Map.of("currentStatus", currentModificationStatus(o.getId()))));
            Payment p = paymentRepository.findByModificationRequestId(m.getId()).orElseThrow();
            if (m.getStatus() == ModificationStatus.AWAITING_SUPPLEMENT) {
                if (p.getStatus() != PaymentStatus.SUBMITTED) {
                    throw OrderException.paymentConflict(p.getStatus());
                }
                OffsetDateTime availableAt = submissionRepository.findFirstByPaymentIdAndStatus(p.getId(), SubmissionStatus.SUBMITTED)
                        .map(PaymentSubmission::getSubmittedAt).orElse(now).plus(settings.modificationTimeout());
                if (now.isBefore(availableAt)) {
                    throw new OrderException(OrderErrorCode.PAYMENT_STATE_CONFLICT,
                            Map.of("currentStatus", p.getStatus().name(), "disputeAvailableAt", availableAt.toString()));
                }
            }
            OrderStatus from = o.getStatus();
            o.setStatus(OrderStatus.DISPUTED);
            o.setDisputedAt(now);
            AdminTask task = adminTaskRepository.saveAndFlush(AdminTask.builder().type(AdminTaskType.MODIFICATION_PAYMENT_DISPUTE)
                    .orderId(o.getId()).paymentId(p.getId()).status(AdminTaskStatus.OPEN).openedBy(actor.userId())
                    .openedByRole(role).build());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("description", description);
            payload.put("modificationId", m.getId().toString());
            payload.put("paymentId", p.getId().toString());
            payload.put("adminTaskId", task.getId().toString());
            payload.put("transitions", List.of(OrderLogWriter.transition("ORDER", o.getId(), from, OrderStatus.DISPUTED),
                    OrderLogWriter.transition("ADMIN_TASK", task.getId(), null, AdminTaskStatus.OPEN)));
            orderLogWriter.write(locked, "PAYMENT_DISPUTE_OPENED", new Actor(role, actor.userId()), payload);
            if (role == ActorRole.OWNER) {
                notifier.sitter(o, "PAYMENT_DISPUTE_OPENED", false, Map.of());
            } else {
                notifier.owner(o, "PAYMENT_DISPUTE_OPENED", false, Map.of());
            }
            ModificationResponses.Disputed resp = new ModificationResponses.Disputed(o.getId(), OrderStatus.DISPUTED.name(), task.getId());
            support.complete(claim, 200, resp);
            return resp;
        });
    }

    private String currentModificationStatus(UUID orderId) {
        return modificationRequestRepository.findFirstByOrderIdAndStatusIn(orderId, ModificationRequest.OPEN_STATUSES)
                .map(m -> m.getStatus().name()).orElse("NONE");
    }

    // ------------------------------------------------------------------ E25

    public ModificationResponses.Completed complete(UUID orderId) {
        CurrentActor actor = CurrentActor.fromToken();
        Order order = support.loadOrder(orderId);
        OrderAccess.requireOwner(order, actor);
        IdempotencyContext ctx = validator.requireIdempotencyKey();
        IdempotencyClaim claim = support.claim(ctx);
        return support.inLockedTx(orderId, locked -> {
            Order o = locked.entity();
            OffsetDateTime now = clock.now();
            requireInProgressWithoutOpenModification(o);
            List<VisitView> effective = visitQueryService.listVisits(o.getId()).stream().filter(VisitView::effective).toList();
            List<String> unfinished = effective.stream()
                    .filter(v -> !(v.status() == VisitStatus.CLOSED_BY_SYSTEM || (v.status() == VisitStatus.DONE && v.careLogSubmitted())))
                    .map(v -> v.visitId().toString()).toList();
            if (effective.isEmpty() || !unfinished.isEmpty()) {
                throw new OrderException(OrderErrorCode.VISITS_NOT_FINISHED, Map.of("visitIds", unfinished));
            }
            complete(locked, ActorRole.OWNER, actor.userId(), now);
            notifier.sitter(o, "ORDER_COMPLETED_BY_OWNER", false, Map.of());
            ModificationResponses.Completed resp = new ModificationResponses.Completed(o.getId(), OrderStatus.COMPLETED.name(), now);
            support.complete(claim, 200, resp);
            return resp;
        });
    }

    private void requireInProgressWithoutOpenModification(Order o) {
        if (o.getStatus() != OrderStatus.IN_PROGRESS) {
            throw OrderException.stateConflict(o.getStatus());
        }
        if (modificationRequestRepository.existsByOrderIdAndStatusIn(o.getId(), ModificationRequest.OPEN_STATUSES)) {
            throw new OrderException(OrderErrorCode.MODIFICATION_IN_PROGRESS);
        }
    }

    private void complete(LockedOrder locked, ActorRole by, UUID userId, OffsetDateTime now) {
        Order o = locked.entity();
        o.setStatus(OrderStatus.COMPLETED);
        o.setCompletedBy(by);
        o.setCompletedAt(now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("completedBy", by.name());
        payload.put("completionBasisAt", o.getCompletionBasisAt() == null ? null : o.getCompletionBasisAt().toString());
        payload.put("transitions", List.of(OrderLogWriter.transition("ORDER", o.getId(), OrderStatus.IN_PROGRESS, OrderStatus.COMPLETED)));
        orderLogWriter.write(locked, "ORDER_COMPLETED", by == ActorRole.SYSTEM ? Actor.SYSTEM : new Actor(by, userId), payload);
    }

    /**
     * C8 O12 逐筆（排程鎖後重驗）：IN_PROGRESS、有效行程全為 DONE / CLOSED_BY_SYSTEM（不看日誌，CQ-40）、無未結束變更、
     * {@code now ≥ completion_basis_at + AUTO_COMPLETE_HOURS}。回傳是否處理。
     */
    public boolean autoCompleteInTx(LockedOrder locked) {
        Order o = locked.entity();
        OffsetDateTime now = clock.now();
        if (o.getStatus() != OrderStatus.IN_PROGRESS || o.getCompletionBasisAt() == null
                || now.isBefore(o.getCompletionBasisAt().plus(settings.autoComplete()))
                || modificationRequestRepository.existsByOrderIdAndStatusIn(o.getId(), ModificationRequest.OPEN_STATUSES)) {
            return false;
        }
        List<VisitView> effective = visitQueryService.listVisits(o.getId()).stream().filter(VisitView::effective).toList();
        if (effective.isEmpty() || !effective.stream().allMatch(VisitView::finished)) {
            return false;
        }
        complete(locked, ActorRole.SYSTEM, null, now);
        notifier.both(o, "ORDER_AUTO_COMPLETED", false, Map.of());
        return true;
    }

    // ------------------------------------------------------------------ E26

    public ModificationResponses.Disputed serviceDispute(UUID orderId, ModificationRequests.Description req) {
        CurrentActor actor = CurrentActor.fromToken();
        Order order = support.loadOrder(orderId);
        OrderAccess.requireOwner(order, actor);
        IdempotencyContext ctx = validator.requireIdempotencyKey();
        String description = validator.requiredText(req == null ? null : req.description(), "description", 500);
        IdempotencyClaim claim = support.claim(ctx);
        return support.inLockedTx(orderId, locked -> {
            Order o = locked.entity();
            OffsetDateTime now = clock.now();
            requireInProgressWithoutOpenModification(o);
            List<VisitView> effective = visitQueryService.listVisits(o.getId()).stream().filter(VisitView::effective).toList();
            List<String> unfinished = effective.stream().filter(v -> !v.finished()).map(v -> v.visitId().toString()).toList();
            if (effective.isEmpty() || !unfinished.isEmpty() || o.getCompletionBasisAt() == null) {
                throw new OrderException(OrderErrorCode.VISITS_NOT_FINISHED, Map.of("visitIds", unfinished));
            }
            OffsetDateTime windowEnds = o.getCompletionBasisAt().plus(settings.disputeWindow());
            if (!now.isBefore(windowEnds)) {
                throw OrderException.deadline("DISPUTE_WINDOW_ENDS_AT", windowEnds);
            }
            o.setStatus(OrderStatus.DISPUTED);
            o.setDisputedAt(now);
            AdminTask task = adminTaskRepository.saveAndFlush(AdminTask.builder().type(AdminTaskType.SERVICE_DISPUTE)
                    .orderId(o.getId()).status(AdminTaskStatus.OPEN).openedBy(actor.userId()).openedByRole(ActorRole.OWNER).build());
            orderLogWriter.write(locked, "SERVICE_DISPUTE_OPENED", Actor.owner(actor.userId()),
                    Map.of("description", description, "adminTaskId", task.getId().toString(),
                            "transitions", List.of(OrderLogWriter.transition("ORDER", o.getId(), OrderStatus.IN_PROGRESS, OrderStatus.DISPUTED),
                                    OrderLogWriter.transition("ADMIN_TASK", task.getId(), null, AdminTaskStatus.OPEN))));
            notifier.sitter(o, "SERVICE_DISPUTE_OPENED", false, Map.of());
            ModificationResponses.Disputed resp = new ModificationResponses.Disputed(o.getId(), OrderStatus.DISPUTED.name(), task.getId());
            support.complete(claim, 200, resp);
            return resp;
        });
    }

    Optional<Payment> modificationPayment(ModificationRequest m) {
        return paymentRepository.findByModificationRequestId(m.getId());
    }
}
