package com.petsitter.application.service.order;

import com.petsitter.application.common.idempotency.IdempotencyClaim;
import com.petsitter.application.common.idempotency.IdempotencyContext;
import com.petsitter.application.dto.order.ModificationRequests;
import com.petsitter.application.dto.order.ModificationResponses;
import com.petsitter.application.exception.OrderErrorCode;
import com.petsitter.application.exception.OrderException;
import com.petsitter.application.exception.PayoutAccountMissingException;
import com.petsitter.application.port.PayoutAccountQuery;
import com.petsitter.application.port.SubscriptionQuery.SubscriptionStatus;
import com.petsitter.application.service.fulfill.VisitCommandService;
import com.petsitter.domain.model.order.ActorRole;
import com.petsitter.domain.model.order.InitiatedBy;
import com.petsitter.domain.model.order.ModificationContent;
import com.petsitter.domain.model.order.ModificationRequest;
import com.petsitter.domain.model.order.ModificationStatus;
import com.petsitter.domain.model.order.ModificationType;
import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.model.order.Payment;
import com.petsitter.domain.model.order.PaymentDirection;
import com.petsitter.domain.model.order.PaymentMethod;
import com.petsitter.domain.model.order.PaymentPurpose;
import com.petsitter.domain.model.order.PaymentStatus;
import com.petsitter.domain.repository.order.ModificationRequestRepository;
import com.petsitter.domain.repository.order.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 變更子流程寫入端點 E17（T1 / T2）、E20（T3）、E21（T4）、E22（T5）、E23（同意三分支）
 * （SD-ORDER §3.3、§4.3、§6.3；PRD-016、PRD-023）。鎖定 / 停權不擋（PRD-024 AC-2.8），變更計價鎖定時依 FREE。
 */
@Service
@RequiredArgsConstructor
public class ModificationService {

    private final OrderCommandSupport support;
    private final OrderRequestValidator validator;
    private final ModificationPlanner planner;
    private final ModificationRequestRepository modificationRequestRepository;
    private final PaymentRepository paymentRepository;
    private final VisitCommandService visitCommandService;
    private final ModificationApplier modificationApplier;
    private final OrderAggregateSupport aggregateSupport;
    private final PayoutAccountQuery payoutAccountQuery;
    private final PayoutSnapshotCipher cipher;
    private final PlanPolicy planPolicy;
    private final OrderLogWriter orderLogWriter;
    private final OrderNotifier notifier;
    private final OrderSettings settings;
    private final OrderClock clock;

    // ------------------------------------------------------------------ E17

    public ModificationResponses.Initiated initiate(UUID orderId, ModificationRequests.Initiate req) {
        CurrentActor actor = CurrentActor.fromToken();
        Order order = support.loadOrder(orderId);
        ActorRole role = OrderAccess.partyRole(order, actor);
        boolean sitter = role == ActorRole.SITTER;
        IdempotencyContext ctx = validator.requireIdempotencyKey();
        if (req == null) {
            throw OrderException.validation("type");
        }
        ModificationType type = planner.type(req.type());
        String reason = validator.requiredText(req.reason(), "reason", 200);
        ModificationPlanner.Draft draft = planner.parse(type, reason, req.removeVisitIds(), req.addItems(),
                req.surchargeAmount(), req.discountAmount(), req.adjustmentReason(), sitter);
        support.readOnly(() -> {
            ModificationPlanner.Context c = planner.context(order);
            planner.validateServerData(draft, planner.content(draft, c), c, sitter);
            return null;
        });
        IdempotencyClaim claim = support.claim(ctx);
        return support.inLockedTx(orderId, locked -> {
            Order o = locked.entity();
            OffsetDateTime now = clock.now();
            requireModifiable(o);
            ModificationPlanner.Context c = planner.context(o);
            ModificationContent content = planner.content(draft, c);
            planner.validateServerData(draft, content, c, sitter);
            if (modificationRequestRepository.existsByOrderIdAndStatusIn(o.getId(), ModificationRequest.OPEN_STATUSES)) {
                throw new OrderException(OrderErrorCode.MODIFICATION_IN_PROGRESS);
            }
            if (c.pendingIds().isEmpty()) {
                throw new OrderException(OrderErrorCode.MODIFICATION_NOT_ALLOWED);
            }
            planner.requireRemovablePending(content, c);
            SubscriptionStatus sub = planPolicy.status(o.getSitterId());
            planner.validateBusiness(draft, content, c, sub, sitter);
            if (sitter && draft.surcharge() + draft.discount() > 0 && !planPolicy.adjustmentAllowed(sub)) {
                throw new OrderException(OrderErrorCode.PLAN_REQUIRED);
            }
            ModificationRequest.ModificationRequestBuilder<?, ?> b = ModificationRequest.builder()
                    .orderId(o.getId())
                    .type(type)
                    .initiatedBy(sitter ? InitiatedBy.SITTER : InitiatedBy.OWNER)
                    .initiatedByUserId(actor.userId())
                    .reason(reason)
                    .expiresAt(now.plus(settings.modificationTimeout()));
            Map<String, Object> payload = new LinkedHashMap<>();
            ModificationResponses.Initiated resp;
            if (sitter) {
                int system = planner.systemAmount(content, c);
                int newTotal = system + draft.surcharge() - draft.discount();
                int net = aggregateSupport.netPaid(o);
                ModificationRequest m = modificationRequestRepository.save(b.status(ModificationStatus.QUOTED)
                        .quotedContent(content).systemAmount(system).surchargeAmount(draft.surcharge())
                        .discountAmount(draft.discount()).adjustmentReason(draft.adjustmentReason()).newTotalAmount(newTotal)
                        .quotedNetPaid(net).quotedDiff(newTotal - net).quotedAt(now).build());
                payload.put("modificationId", m.getId().toString());
                payload.put("type", type.name());
                payload.put("reason", reason);
                payload.put("quotedContent", ModificationApplier.describe(content));
                putQuote(payload, m, planPolicy.adjustmentAllowed(sub));
                payload.put("transitions", List.of(OrderLogWriter.transition("MODIFICATION", m.getId(), null, ModificationStatus.QUOTED)));
                orderLogWriter.write(locked, "MODIFICATION_PROPOSED", Actor.sitter(actor.userId()), payload);
                notifier.modification(o, "OWNER", m.getId(), "MODIFICATION_PROPOSED", Map.of());
                resp = new ModificationResponses.Initiated(m.getId(), m.getStatus().name(), m.getInitiatedBy().name(),
                        m.getExpiresAt(), system, newTotal, net, newTotal - net);
            } else {
                ModificationRequest m = modificationRequestRepository.save(b.status(ModificationStatus.REQUESTED)
                        .requestedContent(content).build());
                payload.put("modificationId", m.getId().toString());
                payload.put("type", type.name());
                payload.put("reason", reason);
                payload.put("requestedContent", ModificationApplier.describe(content));
                payload.put("expiresAt", m.getExpiresAt().toString());
                payload.put("transitions", List.of(OrderLogWriter.transition("MODIFICATION", m.getId(), null, ModificationStatus.REQUESTED)));
                orderLogWriter.write(locked, "MODIFICATION_REQUESTED", Actor.owner(actor.userId()), payload);
                notifier.modification(o, "SITTER", m.getId(), "MODIFICATION_REQUESTED", Map.of());
                resp = new ModificationResponses.Initiated(m.getId(), m.getStatus().name(), m.getInitiatedBy().name(),
                        m.getExpiresAt(), null, null, null, null);
            }
            support.complete(claim, 201, resp);
            return resp;
        });
    }

    private static void requireModifiable(Order o) {
        if (o.getStatus() != OrderStatus.CONFIRMED && o.getStatus() != OrderStatus.IN_PROGRESS) {
            throw OrderException.stateConflict(o.getStatus());
        }
    }

    private static void putQuote(Map<String, Object> payload, ModificationRequest m, boolean custom) {
        payload.put("systemAmount", m.getSystemAmount());
        payload.put("surchargeAmount", m.getSurchargeAmount());
        payload.put("discountAmount", m.getDiscountAmount());
        payload.put("adjustmentReason", m.getAdjustmentReason());
        payload.put("newTotalAmount", m.getNewTotalAmount());
        payload.put("netPaid", m.getQuotedNetPaid());
        payload.put("diff", m.getQuotedDiff());
        payload.put("pricingMode", custom ? "CUSTOM_ALLOWED" : "SYSTEM_ONLY");
        payload.put("expiresAt", m.getExpiresAt().toString());
    }

    // ------------------------------------------------------------------ E20

    public ModificationResponses.Quoted quote(UUID orderId, UUID modificationId, ModificationRequests.Quote req) {
        CurrentActor actor = CurrentActor.fromToken();
        Order order = support.loadOrder(orderId);
        ModificationRequest probe = support.loadModification(order, modificationId);
        OrderAccess.requireSitter(order, actor);
        IdempotencyContext ctx = validator.requireIdempotencyKey();
        ModificationRequests.Quote r = req == null ? new ModificationRequests.Quote(null, null, null, null, null) : req;
        ModificationPlanner.Draft draft = planner.parse(probe.getType(), probe.getReason(), r.removeVisitIds(), r.addItems(),
                r.surchargeAmount(), r.discountAmount(), r.adjustmentReason(), true);
        support.readOnly(() -> {
            ModificationPlanner.Context c = planner.context(order);
            planner.validateServerData(draft, planner.content(draft, c), c, true);
            return null;
        });
        IdempotencyClaim claim = support.claim(ctx);
        return support.inLockedTx(orderId, locked -> {
            Order o = locked.entity();
            OffsetDateTime now = clock.now();
            ModificationPlanner.Context c = planner.context(o);
            ModificationContent content = planner.content(draft, c);
            planner.validateServerData(draft, content, c, true);
            requireModifiable(o);
            ModificationRequest m = support.loadModification(o, modificationId);
            if (m.getStatus() != ModificationStatus.REQUESTED) {
                throw OrderException.modificationConflict(m.getStatus());
            }
            requireNotExpired(m, now);
            planner.requireRemovablePending(content, c);
            SubscriptionStatus sub = planPolicy.status(o.getSitterId());
            planner.validateBusiness(draft, content, c, sub, true);
            if (draft.surcharge() + draft.discount() > 0 && !planPolicy.adjustmentAllowed(sub)) {
                throw new OrderException(OrderErrorCode.PLAN_REQUIRED);
            }
            int system = planner.systemAmount(content, c);
            int newTotal = system + draft.surcharge() - draft.discount();
            int net = aggregateSupport.netPaid(o);
            m.setStatus(ModificationStatus.QUOTED);
            m.setQuotedContent(content);
            m.setSystemAmount(system);
            m.setSurchargeAmount(draft.surcharge());
            m.setDiscountAmount(draft.discount());
            m.setAdjustmentReason(draft.adjustmentReason());
            m.setNewTotalAmount(newTotal);
            m.setQuotedNetPaid(net);
            m.setQuotedDiff(newTotal - net);
            m.setQuotedAt(now);
            m.setExpiresAt(now.plus(settings.modificationTimeout()));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("modificationId", m.getId().toString());
            payload.put("type", m.getType().name());
            payload.put("quotedContent", ModificationApplier.describe(content));
            putQuote(payload, m, planPolicy.adjustmentAllowed(sub));
            payload.put("transitions", List.of(OrderLogWriter.transition("MODIFICATION", m.getId(),
                    ModificationStatus.REQUESTED, ModificationStatus.QUOTED)));
            orderLogWriter.write(locked, "MODIFICATION_QUOTED", Actor.sitter(actor.userId()), payload);
            notifier.modification(o, "OWNER", m.getId(), "MODIFICATION_QUOTED", Map.of());
            ModificationResponses.Quoted resp = new ModificationResponses.Quoted(m.getId(), m.getStatus().name(), system,
                    newTotal, net, newTotal - net, m.getExpiresAt());
            support.complete(claim, 200, resp);
            return resp;
        });
    }

    private static void requireNotExpired(ModificationRequest m, OffsetDateTime now) {
        if (m.getExpiresAt() != null && !now.isBefore(m.getExpiresAt())) {
            throw OrderException.deadline("MODIFICATION_EXPIRES_AT", m.getExpiresAt());
        }
    }

    // ------------------------------------------------------------------ E21

    public ModificationResponses.Ended reject(UUID orderId, UUID modificationId, ModificationRequests.Reject req) {
        CurrentActor actor = CurrentActor.fromToken();
        Order order = support.loadOrder(orderId);
        support.loadModification(order, modificationId);
        ActorRole role = OrderAccess.partyRole(order, actor);
        IdempotencyContext ctx = validator.requireIdempotencyKey();
        String reason = role == ActorRole.SITTER
                ? validator.requiredText(req == null ? null : req.reason(), "reason", 200) : null;
        IdempotencyClaim claim = support.claim(ctx);
        return support.inLockedTx(orderId, locked -> {
            Order o = locked.entity();
            OffsetDateTime now = clock.now();
            requireModifiable(o);
            ModificationRequest m = support.loadModification(o, modificationId);
            ModificationStatus allowed = role == ActorRole.SITTER ? ModificationStatus.REQUESTED : ModificationStatus.QUOTED;
            if (m.getStatus() != allowed) {
                throw OrderException.modificationConflict(m.getStatus());
            }
            requireNotExpired(m, now);
            ModificationStatus from = m.getStatus();
            m.setStatus(ModificationStatus.REJECTED);
            m.setEndedAt(now);
            m.setRejectReason(reason);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("modificationId", m.getId().toString());
            payload.put("reason", reason);
            payload.put("transitions", List.of(OrderLogWriter.transition("MODIFICATION", m.getId(), from, ModificationStatus.REJECTED)));
            orderLogWriter.write(locked, "MODIFICATION_REJECTED", new Actor(role, actor.userId()), payload);
            if (role == ActorRole.SITTER) {
                notifier.modification(o, "OWNER", m.getId(), "MODIFICATION_REJECTED_BY_SITTER", Map.of("reason", reason));
            } else {
                notifier.modification(o, "SITTER", m.getId(), "MODIFICATION_REJECTED_BY_OWNER", Map.of());
            }
            ModificationResponses.Ended resp = new ModificationResponses.Ended(m.getId(), m.getStatus().name(), now);
            support.complete(claim, 200, resp);
            return resp;
        });
    }

    // ------------------------------------------------------------------ E22

    public ModificationResponses.Ended withdraw(UUID orderId, UUID modificationId) {
        CurrentActor actor = CurrentActor.fromToken();
        Order order = support.loadOrder(orderId);
        ModificationRequest probe = support.loadModification(order, modificationId);
        ActorRole role = OrderAccess.partyRole(order, actor);
        boolean initiator = probe.getInitiatedBy() != InitiatedBy.SYSTEM
                && probe.getInitiatedBy().name().equals(role.name())
                && actor.userId().equals(probe.getInitiatedByUserId());
        if (!initiator) {
            throw OrderException.forbidden();
        }
        IdempotencyContext ctx = validator.requireIdempotencyKey();
        IdempotencyClaim claim = support.claim(ctx);
        return support.inLockedTx(orderId, locked -> {
            Order o = locked.entity();
            OffsetDateTime now = clock.now();
            requireModifiable(o);
            ModificationRequest m = support.loadModification(o, modificationId);
            if (m.getStatus() != ModificationStatus.REQUESTED && m.getStatus() != ModificationStatus.QUOTED) {
                throw OrderException.modificationConflict(m.getStatus());
            }
            requireNotExpired(m, now);
            ModificationStatus from = m.getStatus();
            m.setStatus(ModificationStatus.WITHDRAWN);
            m.setEndedAt(now);
            orderLogWriter.write(locked, "MODIFICATION_WITHDRAWN", new Actor(role, actor.userId()),
                    Map.of("modificationId", m.getId().toString(), "fromStatus", from.name(),
                            "transitions", List.of(OrderLogWriter.transition("MODIFICATION", m.getId(), from, ModificationStatus.WITHDRAWN))));
            notifier.modification(o, role == ActorRole.OWNER ? "SITTER" : "OWNER", m.getId(), "MODIFICATION_WITHDRAWN",
                    Map.of("initiatedBy", role.name()));
            ModificationResponses.Ended resp = new ModificationResponses.Ended(m.getId(), m.getStatus().name(), now);
            support.complete(claim, 200, resp);
            return resp;
        });
    }

    // ------------------------------------------------------------------ E23

    public ModificationResponses.Accepted accept(UUID orderId, UUID modificationId) {
        CurrentActor actor = CurrentActor.fromToken();
        Order order = support.loadOrder(orderId);
        support.loadModification(order, modificationId);
        OrderAccess.requireOwner(order, actor);
        IdempotencyContext ctx = validator.requireIdempotencyKey();
        IdempotencyClaim claim = support.claim(ctx);
        return support.inLockedTx(orderId, locked -> {
            Order o = locked.entity();
            OffsetDateTime now = clock.now();
            requireModifiable(o);
            ModificationRequest m = support.loadModification(o, modificationId);
            if (m.getStatus() != ModificationStatus.QUOTED) {
                throw OrderException.modificationConflict(m.getStatus());
            }
            requireNotExpired(m, now);
            ModificationContent content = m.getQuotedContent() == null ? ModificationContent.empty() : m.getQuotedContent();
            List<Map<String, Object>> transitions = new ArrayList<>();
            // 減少目標立即取消（不觸發 O9）；任一非 PENDING → VisitNotPendingException → 409 VISIT_ALREADY_STARTED（整筆回滾）
            visitCommandService.cancelVisits(locked, new HashSet<>(content.removeVisitIds()), now);
            content.removeVisitIds().forEach(v -> transitions.add(OrderLogWriter.transition("VISIT", v, "PENDING", "CANCELLED")));
            int net = aggregateSupport.netPaid(o);
            int diff = m.getNewTotalAmount() - net;
            m.setAgreedNetPaid(net);
            m.setAgreedDiff(diff);
            m.setAgreedAt(now);
            OrderStatus orderBefore = o.getStatus();
            Payment payment = null;
            List<UUID> created = List.of();
            String branch;
            if (diff == 0) {
                branch = ModificationStatus.SETTLED.name();
                ModificationApplier.SettleResult r = modificationApplier.settle(locked, m);
                created = r.createdVisitIds();
                created.forEach(v -> transitions.add(OrderLogWriter.transition("VISIT", v, null, "PENDING")));
                if (r.orderCancelled()) {
                    transitions.add(OrderLogWriter.transition("ORDER", o.getId(), orderBefore, OrderStatus.CANCELLED));
                }
            } else if (diff > 0) {
                branch = ModificationStatus.AWAITING_SUPPLEMENT.name();
                m.setStatus(ModificationStatus.AWAITING_SUPPLEMENT);
                PayoutAccountQuery.PayoutAccountSnapshot account = payoutAccountQuery.getSnapshot(o.getSitterId())
                        .orElseThrow(() -> new PayoutAccountMissingException("payout account missing for sitter of order " + o.getId()));
                modificationRequestRepository.flush();
                UUID supplementPaymentId = UUID.randomUUID(); // SD-ORDER §8.2：加密前產生（AAD 綁定該列）
                payment = paymentRepository.save(Payment.builder()
                        .id(supplementPaymentId)
                        .orderId(o.getId()).modificationRequestId(m.getId())
                        .direction(PaymentDirection.CHARGE).purpose(PaymentPurpose.SUPPLEMENT).method(PaymentMethod.OFFLINE_TRANSFER)
                        .amount(diff).status(PaymentStatus.UNPAID).dueAt(now.plus(settings.modificationTimeout()))
                        .payeeAccountSnapshot(cipher.encrypt(account, supplementPaymentId)).payeeAccountKeyVersion(PayoutSnapshotCipher.KEY_VERSION)
                        .createdBy(actor.userId()).build());
                aggregateSupport.recomputeCompletionBasis(o);
            } else {
                branch = ModificationStatus.AWAITING_REFUND.name();
                m.setStatus(ModificationStatus.AWAITING_REFUND);
                modificationRequestRepository.flush();
                payment = paymentRepository.save(Payment.builder()
                        .orderId(o.getId()).modificationRequestId(m.getId())
                        .direction(PaymentDirection.REFUND).purpose(PaymentPurpose.MODIFICATION_REFUND).method(PaymentMethod.OFFLINE_TRANSFER)
                        .amount(-diff).status(PaymentStatus.UNPAID).createdBy(actor.userId()).build());
                aggregateSupport.recomputeCompletionBasis(o);
            }
            transitions.add(OrderLogWriter.transition("MODIFICATION", m.getId(), ModificationStatus.QUOTED, m.getStatus()));
            if (payment != null) {
                transitions.add(OrderLogWriter.transition("PAYMENT", payment.getId(), null, PaymentStatus.UNPAID));
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("modificationId", m.getId().toString());
            payload.put("branch", branch);
            payload.put("agreedNetPaid", net);
            payload.put("agreedDiff", diff);
            payload.put("cancelledVisitIds", content.removeVisitIds().stream().map(UUID::toString).toList());
            payload.put("createdVisitIds", created.stream().map(UUID::toString).toList());
            payload.put("paymentId", payment == null ? null : payment.getId().toString());
            payload.put("newTotalAmount", m.getNewTotalAmount());
            payload.put("transitions", transitions);
            orderLogWriter.write(locked, "MODIFICATION_ACCEPTED", Actor.owner(actor.userId()), payload);
            if (diff == 0) {
                notifier.modification(o, "OWNER", m.getId(), "MODIFICATION_SETTLED", Map.of());
                notifier.modification(o, "SITTER", m.getId(), "MODIFICATION_SETTLED", Map.of());
            } else if (diff > 0) {
                String due = payment.getDueAt().atZoneSameInstant(OrderClock.TAIPEI)
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                notifier.modification(o, "OWNER", m.getId(), "SUPPLEMENT_REQUIRED", Map.of("amount", diff, "dueAt", due));
                notifier.modification(o, "SITTER", m.getId(), "MODIFICATION_ACCEPTED_SUPPLEMENT", Map.of("amount", diff));
            } else {
                notifier.modification(o, "SITTER", m.getId(), "REFUND_REQUIRED", Map.of("amount", -diff));
            }
            ModificationResponses.PaymentRef pref = payment == null ? null : new ModificationResponses.PaymentRef(payment.getId(),
                    payment.getDirection().name(), payment.getPurpose().name(), payment.getAmount(), payment.getStatus().name(),
                    payment.getDueAt());
            ModificationResponses.Accepted resp = new ModificationResponses.Accepted(m.getId(), m.getStatus().name(), net, diff,
                    pref, o.getStatus().name(), o.getTotalAmount());
            support.complete(claim, 200, resp);
            return resp;
        });
    }
}
