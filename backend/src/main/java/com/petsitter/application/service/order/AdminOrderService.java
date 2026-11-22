package com.petsitter.application.service.order;

import com.petsitter.application.common.idempotency.IdempotencyClaim;
import com.petsitter.application.common.idempotency.IdempotencyContext;
import com.petsitter.application.dto.order.ModificationRequests;
import com.petsitter.application.dto.order.ModificationResponses;
import com.petsitter.application.dto.order.OrderDetailResponse;
import com.petsitter.application.dto.order.PageResponse;
import com.petsitter.application.exception.OrderErrorCode;
import com.petsitter.application.exception.OrderException;
import com.petsitter.application.service.fulfill.VisitCommandService;
import com.petsitter.application.service.fulfill.VisitQueryService;
import com.petsitter.application.service.fulfill.VisitView;
import com.petsitter.domain.model.order.ActorRole;
import com.petsitter.domain.model.order.AdminTask;
import com.petsitter.domain.model.order.AdminTaskResolution;
import com.petsitter.domain.model.order.AdminTaskStatus;
import com.petsitter.domain.model.order.AdminTaskType;
import com.petsitter.domain.model.order.CancelReasonCode;
import com.petsitter.domain.model.order.ModificationRequest;
import com.petsitter.domain.model.order.ModificationStatus;
import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.model.order.Payment;
import com.petsitter.domain.model.order.PaymentDirection;
import com.petsitter.domain.model.order.PaymentMethod;
import com.petsitter.domain.model.order.PaymentPurpose;
import com.petsitter.domain.model.order.PaymentStatus;
import com.petsitter.domain.model.order.PaymentSubmission;
import com.petsitter.domain.model.order.SubmissionStatus;
import com.petsitter.domain.model.order.VisitStatus;
import com.petsitter.domain.repository.order.AdminTaskRepository;
import com.petsitter.domain.repository.order.ModificationRequestRepository;
import com.petsitter.domain.repository.order.OrderLogRepository;
import com.petsitter.domain.repository.order.PaymentRepository;
import com.petsitter.domain.repository.order.PaymentSubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ADMIN 端點 E16 O16 作廢、E27 唯讀詳情、E28 待辦列表、E29 O15 / O17 調解（SD-ORDER §3.5、§4.5、§6.3）。
 * 唯讀範圍經 {@link AdminOrderAccessPolicy}；寫入各自另檢查（E16 OPEN 首次付款待辦、E29 DISPUTED）。
 */
@Service
@RequiredArgsConstructor
public class AdminOrderService {

    private final OrderCommandSupport support;
    private final OrderRequestValidator validator;
    private final AdminOrderAccessPolicy accessPolicy;
    private final AdminTaskRepository adminTaskRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentSubmissionRepository submissionRepository;
    private final ModificationRequestRepository modificationRequestRepository;
    private final OrderLogRepository orderLogRepository;
    private final OrderCancellationApplier cancellationApplier;
    private final VisitCommandService visitCommandService;
    private final VisitQueryService visitQueryService;
    private final OrderAggregateSupport aggregateSupport;
    private final OrderDetailService orderDetailService;
    private final ModificationQueryService modificationQueryService;
    private final OrderLogWriter orderLogWriter;
    private final OrderNotifier notifier;
    private final OrderSettings settings;
    private final OrderClock clock;

    // ------------------------------------------------------------------ E16

    public ModificationResponses.AdminVoided voidInitialPayment(UUID orderId, UUID paymentId, ModificationRequests.Void req) {
        CurrentActor actor = CurrentActor.fromToken();
        Order order = support.loadOrder(orderId);
        Payment payment = support.loadPayment(order, paymentId);
        // G5（鎖前）：payment 為 INITIAL 且該訂單有 OPEN 的 INITIAL_PAYMENT_UNVERIFIED 待辦
        if (payment.getPurpose() != PaymentPurpose.INITIAL || adminTaskRepository.findByOrderIdAndTypeAndStatus(orderId,
                AdminTaskType.INITIAL_PAYMENT_UNVERIFIED, AdminTaskStatus.OPEN).isEmpty()) {
            throw OrderException.forbidden();
        }
        IdempotencyContext ctx = validator.requireIdempotencyKey();
        String reason = validator.requiredText(req == null ? null : req.reason(), "reason", 200);
        IdempotencyClaim claim = support.claim(ctx);
        return support.inLockedTx(orderId, locked -> {
            Order o = locked.entity();
            OffsetDateTime now = clock.now();
            Payment p = support.loadPayment(o, paymentId);
            AdminTask task = adminTaskRepository.findByOrderIdAndTypeAndStatus(orderId, AdminTaskType.INITIAL_PAYMENT_UNVERIFIED,
                    AdminTaskStatus.OPEN).orElse(null);
            if (p.getStatus() != PaymentStatus.SUBMITTED || task == null || o.getStatus() != OrderStatus.PENDING_PAYMENT) {
                throw OrderException.paymentConflict(p.getStatus());
            }
            UUID submissionId = submissionRepository.findFirstByPaymentIdAndStatus(p.getId(), SubmissionStatus.SUBMITTED)
                    .map(PaymentSubmission::getId).orElse(null);
            List<Map<String, Object>> transitions = new ArrayList<>(cancellationApplier.apply(locked, ActorRole.ADMIN,
                    CancelReasonCode.ADMIN_VOIDED, reason, actor.userId()));
            p.setVoidReason(reason);
            PaymentReviewService.resolveTask(task, AdminTaskResolution.ADMIN_VOIDED, actor.userId(), now, transitions);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("paymentId", p.getId().toString());
            payload.put("submissionId", submissionId == null ? null : submissionId.toString());
            payload.put("reason", reason);
            payload.put("adminTaskId", task.getId().toString());
            payload.put("transitions", transitions);
            orderLogWriter.write(locked, "PAYMENT_VOIDED_BY_ADMIN", Actor.admin(actor.userId()), payload);
            notifier.both(o, "PAYMENT_VOIDED_BY_ADMIN", true, Map.of());
            ModificationResponses.AdminVoided resp = new ModificationResponses.AdminVoided(o.getId(), OrderStatus.CANCELLED.name(),
                    ActorRole.ADMIN.name(), PaymentStatus.VOID.name());
            support.complete(claim, 200, resp);
            return resp;
        });
    }

    // ------------------------------------------------------------------ E27 / E28

    public OrderDetailResponse detail(UUID orderId) {
        return support.readOnly(() -> {
            Order order = support.loadOrder(orderId);
            accessPolicy.requireReadable(order);
            OrderDetailResponse base = orderDetailService.build(order, ActorRole.ADMIN, clock.now(), true);
            List<String> actions = new ArrayList<>(base.allowedActions());
            if (adminTaskRepository.findByOrderIdAndTypeAndStatus(orderId, AdminTaskType.INITIAL_PAYMENT_UNVERIFIED,
                    AdminTaskStatus.OPEN).isPresent()) {
                actions.add(0, "VOID_INITIAL_PAYMENT");
            }
            List<OrderDetailResponse.AdminTaskItem> tasks = adminTaskRepository.findByOrderIdOrderByCreatedAtAsc(orderId).stream()
                    .map(t -> new OrderDetailResponse.AdminTaskItem(t.getId(), t.getType().name(), t.getStatus().name(),
                            t.getResolution() == null ? null : t.getResolution().name(), t.getCreatedAt())).toList();
            List<OrderDetailResponse.LogItem> logs = orderLogRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId).stream()
                    .map(l -> new OrderDetailResponse.LogItem(l.getActionType(), l.getOperatorRole().name(), l.getOperatorUserId(),
                            l.getPayload(), l.getCreatedAt())).toList();
            return new OrderDetailResponse(base.orderId(), base.status(), base.role(), base.counterpart(), base.firstServiceDate(),
                    base.lastServiceDate(), base.reviewExpiresAt(), base.baseAmount(), base.quote(), base.sitterTier(),
                    base.modificationPricingMode(), base.groups(), base.visits(), base.questionnaireStatus(),
                    base.questionnaireExemptReason(), base.payments(), base.openModification(), base.cancellation(),
                    base.completedAt(), base.disputeWindowEndsAt(), base.netPaid(), base.serverTime(), null, actions,
                    modificationQueryService.summaries(order), tasks, logs);
        });
    }

    public PageResponse<ModificationResponses.AdminTaskItem> tasks(String status, String type, Integer page, Integer size) {
        AdminTaskStatus st;
        AdminTaskType tp = null;
        try {
            st = status == null ? AdminTaskStatus.OPEN : AdminTaskStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw OrderException.validation("status");
        }
        try {
            if (type != null) {
                tp = AdminTaskType.valueOf(type);
            }
        } catch (IllegalArgumentException e) {
            throw OrderException.validation("type");
        }
        int p = page == null ? 0 : page;
        int sz = size == null ? 20 : size;
        if (p < 0) {
            throw OrderException.validation("page");
        }
        if (sz < 1 || sz > 50) {
            throw OrderException.validation("size");
        }
        AdminTaskType ftp = tp;
        return support.readOnly(() -> {
            Page<AdminTask> res = adminTaskRepository.findList(st, ftp, PageRequest.of(p, sz));
            return new PageResponse<>(res.getContent().stream().map(t -> new ModificationResponses.AdminTaskItem(t.getId(),
                    t.getType().name(), t.getOrderId(), t.getPaymentId(), t.getStatus().name(), t.getCreatedAt())).toList(),
                    p, sz, res.getTotalElements());
        });
    }

    // ------------------------------------------------------------------ E29

    record ParsedMediation(Map<UUID, Boolean> decisions, Integer adjustmentAmount, String basis) {
    }

    public ModificationResponses.Mediated mediate(UUID orderId, ModificationRequests.Mediation req) {
        CurrentActor actor = CurrentActor.fromToken();
        Order order = support.loadOrder(orderId);
        accessPolicy.requireReadable(order);
        IdempotencyContext ctx = validator.requireIdempotencyKey();
        ParsedMediation parsed = parse(req);
        requireDecisionsCover(orderId, parsed);
        IdempotencyClaim claim = support.claim(ctx);
        return support.inLockedTx(orderId, locked -> applyMediation(locked, actor, parsed, claim));
    }

    private ParsedMediation parse(ModificationRequests.Mediation req) {
        Map<UUID, Boolean> decisions = new LinkedHashMap<>();
        if (req != null && req.refundDecisions() != null) {
            for (ModificationRequests.RefundDecision d : req.refundDecisions()) {
                if (d == null || d.exported() == null) {
                    throw OrderException.validation("refundDecisions");
                }
                decisions.put(validator.uuid(d.paymentId(), "refundDecisions"), d.exported());
            }
        }
        Integer amount = null;
        String basis = null;
        if (req != null && req.adjustment() != null) {
            amount = validator.amount(req.adjustment().amount(), "adjustment.amount", 1, settings.getMaxAmount(), true, 0);
            basis = validator.requiredText(req.adjustment().basis(), "adjustment.basis", 500);
        }
        return new ParsedMediation(decisions, amount, basis);
    }

    /** 每筆 SUBMITTED 退款都必須標 exported（缺 → 400，payload paymentIds）。鎖外讀值、鎖後重驗。 */
    private void requireDecisionsCover(UUID orderId, ParsedMediation parsed) {
        List<String> missing = paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId).stream()
                .filter(p -> p.getDirection() == PaymentDirection.REFUND && p.getStatus() == PaymentStatus.SUBMITTED)
                .filter(p -> !parsed.decisions().containsKey(p.getId()))
                .map(p -> p.getId().toString()).toList();
        if (!missing.isEmpty()) {
            throw OrderException.validation("refundDecisions", Map.of("paymentIds", missing));
        }
    }

    private ModificationResponses.Mediated applyMediation(LockedOrder locked, CurrentActor actor, ParsedMediation parsed,
                                                          IdempotencyClaim claim) {
        Order o = locked.entity();
        OffsetDateTime now = clock.now();
        if (o.getStatus() != OrderStatus.DISPUTED) {
            throw OrderException.stateConflict(o.getStatus());
        }
        List<VisitView> visits = visitQueryService.listVisits(o.getId());
        List<String> inProgress = visits.stream().filter(v -> v.status() == VisitStatus.IN_PROGRESS)
                .map(v -> v.visitId().toString()).toList();
        if (!inProgress.isEmpty()) {
            throw new OrderException(OrderErrorCode.VISITS_NOT_FINISHED, Map.of("visitIds", inProgress));
        }
        requireDecisionsCover(o.getId(), parsed);
        List<Payment> payments = paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(o.getId());
        List<Payment> exported = payments.stream().filter(p -> p.getDirection() == PaymentDirection.REFUND
                && p.getStatus() == PaymentStatus.SUBMITTED && Boolean.TRUE.equals(parsed.decisions().get(p.getId()))).toList();
        int autoAdjust = exported.stream().mapToInt(Payment::getAmount).sum();
        int max = aggregateSupport.netPaid(o) - autoAdjust;
        if (parsed.adjustmentAmount() != null && parsed.adjustmentAmount() > max) {
            throw new OrderException(OrderErrorCode.INVALID_ADJUSTMENT_AMOUNT, Map.of("maxAmount", Math.max(0, max)));
        }
        List<Map<String, Object>> transitions = new ArrayList<>();
        List<UUID> cancelledVisits = visitCommandService.cancelPendingVisits(locked, now);
        cancelledVisits.forEach(v -> transitions.add(OrderLogWriter.transition("VISIT", v, "PENDING", "CANCELLED")));
        List<String> rejectedMods = new ArrayList<>();
        for (ModificationRequest m : modificationRequestRepository.findByOrderIdInAndStatusIn(Set.of(o.getId()), ModificationRequest.OPEN_STATUSES)) {
            transitions.add(OrderLogWriter.transition("MODIFICATION", m.getId(), m.getStatus(), ModificationStatus.REJECTED));
            m.setStatus(ModificationStatus.REJECTED);
            m.setEndedAt(now);
            rejectedMods.add(m.getId().toString());
        }
        boolean supplementSubmittedVoided = false;
        for (Payment p : payments) {
            if (p.isOpen()) {
                if (p.getStatus() == PaymentStatus.SUBMITTED && p.getPurpose() == PaymentPurpose.SUPPLEMENT) {
                    supplementSubmittedVoided = true;
                }
                transitions.add(OrderLogWriter.transition("PAYMENT", p.getId(), p.getStatus(), PaymentStatus.VOID));
                OrderCancellationApplier.voidPayment(p, now, actor.userId(), "ADMIN_MEDIATED", null);
            }
        }
        // 先結束舊列並 flush，再建 ADMIN_ADJUSTMENT（Hibernate 預設 INSERT 先於 UPDATE，會撞 partial unique，§5.1.2）
        paymentRepository.flush();
        modificationRequestRepository.flush();
        List<UUID> adjustmentIds = new ArrayList<>();
        for (Payment src : exported) {
            Payment adj = paymentRepository.save(Payment.builder().orderId(o.getId()).sourcePaymentId(src.getId())
                    .direction(PaymentDirection.REFUND).purpose(PaymentPurpose.ADMIN_ADJUSTMENT).method(PaymentMethod.ADMIN_ADJUSTMENT)
                    .amount(src.getAmount()).status(PaymentStatus.VERIFIED).verifiedAt(now).verifiedBy(actor.userId())
                    .adjustmentBasis("已匯出退款補登（作廢 " + src.getId() + "）").createdBy(actor.userId()).build());
            adjustmentIds.add(adj.getId());
            transitions.add(OrderLogWriter.transition("PAYMENT", adj.getId(), null, PaymentStatus.VERIFIED));
        }
        if (parsed.adjustmentAmount() != null) {
            Payment adj = paymentRepository.save(Payment.builder().orderId(o.getId())
                    .direction(PaymentDirection.REFUND).purpose(PaymentPurpose.ADMIN_ADJUSTMENT).method(PaymentMethod.ADMIN_ADJUSTMENT)
                    .amount(parsed.adjustmentAmount()).status(PaymentStatus.VERIFIED).verifiedAt(now).verifiedBy(actor.userId())
                    .adjustmentBasis(parsed.basis()).createdBy(actor.userId()).build());
            adjustmentIds.add(adj.getId());
            transitions.add(OrderLogWriter.transition("PAYMENT", adj.getId(), null, PaymentStatus.VERIFIED));
        }
        boolean served = visits.stream().anyMatch(v -> v.status() == VisitStatus.DONE || v.status() == VisitStatus.CLOSED_BY_SYSTEM);
        String outcome;
        if (served) {
            outcome = OrderStatus.COMPLETED.name();
            transitions.add(OrderLogWriter.transition("ORDER", o.getId(), OrderStatus.DISPUTED, OrderStatus.COMPLETED));
            o.setStatus(OrderStatus.COMPLETED);
            o.setCompletedBy(ActorRole.ADMIN);
            o.setCompletedAt(now);
        } else {
            outcome = OrderStatus.CANCELLED.name();
            transitions.addAll(cancellationApplier.apply(locked, ActorRole.ADMIN, CancelReasonCode.ADMIN_MEDIATED, null, actor.userId()));
        }
        List<String> resolved = new ArrayList<>();
        for (AdminTask t : adminTaskRepository.findByOrderIdAndStatus(o.getId(), AdminTaskStatus.OPEN)) {
            PaymentReviewService.resolveTask(t, AdminTaskResolution.ADMIN_MEDIATED, actor.userId(), now, transitions);
            resolved.add(t.getId().toString());
        }
        aggregateSupport.recomputeCompletionBasis(o);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("outcome", outcome);
        payload.put("refundDecisions", parsed.decisions().entrySet().stream()
                .map(e -> Map.of("paymentId", e.getKey().toString(), "exported", e.getValue())).toList());
        payload.put("adjustmentPaymentIds", adjustmentIds.stream().map(UUID::toString).toList());
        payload.put("adjustmentAmount", parsed.adjustmentAmount());
        payload.put("basis", parsed.basis());
        payload.put("rejectedModificationIds", rejectedMods);
        payload.put("cancelledVisitIds", cancelledVisits.stream().map(UUID::toString).toList());
        payload.put("resolvedAdminTaskIds", resolved);
        payload.put("transitions", transitions);
        orderLogWriter.write(locked, "ORDER_MEDIATED", Actor.admin(actor.userId()), payload);
        notifier.both(o, "ORDER_MEDIATED", false, Map.of("outcome", outcome));
        if (supplementSubmittedVoided) {
            notifier.both(o, "SUPPLEMENT_VOIDED_BY_ADMIN", false, Map.of());
        }
        ModificationResponses.Mediated resp = new ModificationResponses.Mediated(o.getId(), outcome, adjustmentIds);
        support.complete(claim, 200, resp);
        return resp;
    }

}
