package com.petsitter.application.service.order;

import com.petsitter.application.service.fulfill.VisitCommandService;
import com.petsitter.application.service.fulfill.VisitExitCause;
import com.petsitter.domain.model.order.ActorRole;
import com.petsitter.domain.model.order.AdminTask;
import com.petsitter.domain.model.order.AdminTaskStatus;
import com.petsitter.domain.model.order.AdminTaskType;
import com.petsitter.domain.model.order.CancelReasonCode;
import com.petsitter.domain.model.order.InitiatedBy;
import com.petsitter.domain.model.order.ModificationContent;
import com.petsitter.domain.model.order.ModificationRequest;
import com.petsitter.domain.model.order.ModificationStatus;
import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.model.order.Payment;
import com.petsitter.domain.model.order.PaymentDirection;
import com.petsitter.domain.model.order.PaymentMethod;
import com.petsitter.domain.model.order.PaymentPurpose;
import com.petsitter.domain.model.order.PaymentStatus;
import com.petsitter.domain.repository.order.AdminTaskRepository;
import com.petsitter.domain.repository.order.ModificationRequestRepository;
import com.petsitter.domain.repository.order.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 排程逐筆處理（SD-ORDER §8.1）：每筆一個 {@code REQUIRED} 交易 → {@code tryLockForCron}（SKIP LOCKED，鎖不到 = skipped）
 * → 鎖後重驗（含訂單非 DISPUTED 防線）→ 不符 skipped、不寫 log、不發通知 → 符合則轉移 + order_logs + enqueue。
 * 回傳 true = processed、false = skipped；例外由協調者計 failed（整筆回滾）。
 */
@Component
@RequiredArgsConstructor
public class OrderCronItemProcessor {

    static final String REVIEW_EXPIRED_REASON = "保母未於期限內回覆";
    static final String SYSTEM_REFUND_REASON = "補款逾期自動退款";

    private final OrderLockService lockService;
    private final PaymentRepository paymentRepository;
    private final ModificationRequestRepository modificationRequestRepository;
    private final AdminTaskRepository adminTaskRepository;
    private final OrderCancellationApplier cancellationApplier;
    private final OrderAggregateSupport aggregateSupport;
    private final ModificationPlanner planner;
    private final VisitCommandService visitCommandService;
    private final OrderLifecycleService lifecycleService;
    private final OrderCompletionService completionService;
    private final OrderLogWriter orderLogWriter;
    private final OrderNotifier notifier;
    private final OrderSettings settings;
    private final OrderClock clock;

    private Optional<Payment> payment(Order o, PaymentPurpose purpose, PaymentStatus status) {
        return paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(o.getId()).stream()
                .filter(p -> p.getPurpose() == purpose && p.getStatus() == status).findFirst();
    }

    /** C1 O4：仍 PENDING 且已到期。 */
    @Transactional
    public boolean reviewExpiry(UUID orderId) {
        Optional<LockedOrder> lo = lockService.tryLockForCron(orderId);
        if (lo.isEmpty()) {
            return false;
        }
        Order o = lo.get().entity();
        OffsetDateTime now = clock.now();
        if (o.getStatus() != OrderStatus.PENDING || now.isBefore(o.getReviewExpiresAt())) {
            return false;
        }
        List<Map<String, Object>> t = cancellationApplier.apply(lo.get(), ActorRole.SYSTEM, CancelReasonCode.REVIEW_EXPIRED,
                REVIEW_EXPIRED_REASON, null);
        orderLogWriter.write(lo.get(), "ORDER_REVIEW_EXPIRED", Actor.SYSTEM,
                Map.of("reviewExpiresAt", o.getReviewExpiresAt().toString(), "transitions", t));
        notifier.owner(o, "REVIEW_EXPIRED_OWNER", false, Map.of());
        notifier.sitter(o, "REVIEW_EXPIRED_SITTER", false, Map.of());
        return true;
    }

    /** C2 付款提醒：INITIAL UNPAID、remind_at ≤ now、未提醒、due_at > now；不寫 order_logs。 */
    @Transactional
    public boolean paymentReminder(UUID orderId) {
        Optional<LockedOrder> lo = lockService.tryLockForCron(orderId);
        if (lo.isEmpty()) {
            return false;
        }
        Order o = lo.get().entity();
        OffsetDateTime now = clock.now();
        Optional<Payment> p = payment(o, PaymentPurpose.INITIAL, PaymentStatus.UNPAID);
        if (o.getStatus() != OrderStatus.PENDING_PAYMENT || p.isEmpty() || p.get().getRemindAt() == null
                || p.get().getRemindedAt() != null || now.isBefore(p.get().getRemindAt()) || !now.isBefore(p.get().getDueAt())) {
            return false;
        }
        p.get().setRemindedAt(now);
        notifier.owner(o, "PAYMENT_REMINDER", true, Map.of("dueAt", taipei(p.get().getDueAt())));
        return true;
    }

    /** C3 O8：INITIAL UNPAID 且 now ≥ due_at，訂單仍 PENDING_PAYMENT（SUBMITTED 不逾期）。 */
    @Transactional
    public boolean initialPaymentExpiry(UUID orderId) {
        Optional<LockedOrder> lo = lockService.tryLockForCron(orderId);
        if (lo.isEmpty()) {
            return false;
        }
        Order o = lo.get().entity();
        OffsetDateTime now = clock.now();
        Optional<Payment> p = payment(o, PaymentPurpose.INITIAL, PaymentStatus.UNPAID);
        if (o.getStatus() != OrderStatus.PENDING_PAYMENT || p.isEmpty() || now.isBefore(p.get().getDueAt())) {
            return false;
        }
        List<Map<String, Object>> t = cancellationApplier.apply(lo.get(), ActorRole.SYSTEM, CancelReasonCode.PAYMENT_EXPIRED, null, null);
        orderLogWriter.write(lo.get(), "PAYMENT_EXPIRED", Actor.SYSTEM,
                Map.of("paymentId", p.get().getId().toString(), "dueAt", p.get().getDueAt().toString(), "transitions", t));
        notifier.both(o, "PAYMENT_EXPIRED", true, Map.of());
        return true;
    }

    /** C4 A1：INITIAL SUBMITTED、escalate_at ≤ now、未升級 → 建 OPEN 待辦（ux_admin_tasks_open 保證不重建）。 */
    @Transactional
    public boolean paymentEscalation(UUID orderId) {
        Optional<LockedOrder> lo = lockService.tryLockForCron(orderId);
        if (lo.isEmpty()) {
            return false;
        }
        Order o = lo.get().entity();
        OffsetDateTime now = clock.now();
        Optional<Payment> p = payment(o, PaymentPurpose.INITIAL, PaymentStatus.SUBMITTED);
        if (o.getStatus() != OrderStatus.PENDING_PAYMENT || p.isEmpty() || p.get().getEscalatedAt() != null
                || now.isBefore(p.get().getEscalateAt())
                || adminTaskRepository.findByOrderIdAndTypeAndStatus(o.getId(), AdminTaskType.INITIAL_PAYMENT_UNVERIFIED, AdminTaskStatus.OPEN).isPresent()) {
            return false;
        }
        AdminTask task = adminTaskRepository.save(AdminTask.builder().type(AdminTaskType.INITIAL_PAYMENT_UNVERIFIED)
                .orderId(o.getId()).paymentId(p.get().getId()).status(AdminTaskStatus.OPEN).openedByRole(ActorRole.SYSTEM).build());
        p.get().setEscalatedAt(now);
        orderLogWriter.write(lo.get(), "PAYMENT_ESCALATED", Actor.SYSTEM, Map.of("paymentId", p.get().getId().toString(),
                "adminTaskId", task.getId().toString(), "escalateAt", p.get().getEscalateAt().toString(),
                "transitions", List.of(OrderLogWriter.transition("ADMIN_TASK", task.getId(), null, AdminTaskStatus.OPEN))));
        notifier.both(o, "PAYMENT_ESCALATED", false, Map.of());
        return true;
    }

    /** C5 T6：REQUESTED / QUOTED 且到期 → EXPIRED（訂單 DISPUTED 略過）。 */
    @Transactional
    public boolean modificationExpiry(UUID orderId) {
        Optional<LockedOrder> lo = lockService.tryLockForCron(orderId);
        if (lo.isEmpty()) {
            return false;
        }
        Order o = lo.get().entity();
        OffsetDateTime now = clock.now();
        if (o.getStatus() == OrderStatus.DISPUTED) {
            return false;
        }
        Optional<ModificationRequest> m = modificationRequestRepository.findFirstByOrderIdAndStatusIn(o.getId(),
                List.of(ModificationStatus.REQUESTED, ModificationStatus.QUOTED));
        if (m.isEmpty() || now.isBefore(m.get().getExpiresAt())) {
            return false;
        }
        ModificationRequest mod = m.get();
        ModificationStatus from = mod.getStatus();
        mod.setStatus(ModificationStatus.EXPIRED);
        mod.setEndedAt(now);
        orderLogWriter.write(lo.get(), "MODIFICATION_EXPIRED", Actor.SYSTEM, Map.of("modificationId", mod.getId().toString(),
                "fromStatus", from.name(), "expiresAt", mod.getExpiresAt().toString(),
                "transitions", List.of(OrderLogWriter.transition("MODIFICATION", mod.getId(), from, ModificationStatus.EXPIRED))));
        notifier.modification(o, "OWNER", mod.getId(), "MODIFICATION_EXPIRED", Map.of());
        notifier.modification(o, "SITTER", mod.getId(), "MODIFICATION_EXPIRED", Map.of());
        return true;
    }

    /**
     * C6 T8 / T9 / O10：補款 UNPAID 且 now ≥ due_at、變更 AWAITING_SUPPLEMENT → payment VOID、變更 EXPIRED → flush →
     * {@code total = max(0, total − Σ 本變更減少目標的快照單價)} → 已收淨額 > 新 total 時建 SYSTEM 退款變更與退款 payment，
     * 否則檢查 O10。整筆同交易。
     */
    @Transactional
    public boolean supplementExpiry(UUID orderId) {
        Optional<LockedOrder> lo = lockService.tryLockForCron(orderId);
        if (lo.isEmpty()) {
            return false;
        }
        LockedOrder locked = lo.get();
        Order o = locked.entity();
        OffsetDateTime now = clock.now();
        if (o.getStatus() == OrderStatus.DISPUTED) {
            return false;
        }
        Optional<Payment> sp = payment(o, PaymentPurpose.SUPPLEMENT, PaymentStatus.UNPAID);
        if (sp.isEmpty() || now.isBefore(sp.get().getDueAt())) {
            return false;
        }
        ModificationRequest mod = modificationRequestRepository.findById(sp.get().getModificationRequestId()).orElseThrow();
        if (mod.getStatus() != ModificationStatus.AWAITING_SUPPLEMENT) {
            return false;
        }
        List<Map<String, Object>> t = new ArrayList<>();
        OrderCancellationApplier.voidPayment(sp.get(), now, null, "SUPPLEMENT_EXPIRED", null);
        t.add(OrderLogWriter.transition("PAYMENT", sp.get().getId(), PaymentStatus.UNPAID, PaymentStatus.VOID));
        mod.setStatus(ModificationStatus.EXPIRED);
        mod.setEndedAt(now);
        t.add(OrderLogWriter.transition("MODIFICATION", mod.getId(), ModificationStatus.AWAITING_SUPPLEMENT, ModificationStatus.EXPIRED));
        // 先結束舊列並 flush，再建新列（避開 ux_modreq_open_per_order / ux_payments_open_per_order，§5.1.2）
        paymentRepository.flush();
        modificationRequestRepository.flush();
        ModificationContent content = mod.getQuotedContent() == null ? ModificationContent.empty() : mod.getQuotedContent();
        int before = o.getTotalAmount();
        int after = Math.max(0, before - planner.removedAmount(content, planner.context(o)));
        o.setTotalAmount(after);
        int net = aggregateSupport.netPaid(o);
        UUID refundModId = null;
        UUID refundPayId = null;
        Integer refundAmount = null;
        if (net > after) {
            refundAmount = net - after;
            ModificationRequest sys = modificationRequestRepository.save(ModificationRequest.builder()
                    .orderId(o.getId()).type(com.petsitter.domain.model.order.ModificationType.CHANGE)
                    .initiatedBy(InitiatedBy.SYSTEM).sourceModificationId(mod.getId()).reason(SYSTEM_REFUND_REASON)
                    .quotedContent(ModificationContent.empty()).systemAmount(after).newTotalAmount(after)
                    .surchargeAmount(0).discountAmount(0).quotedNetPaid(net).agreedNetPaid(net)
                    .quotedDiff(after - net).agreedDiff(after - net).quotedAt(now).agreedAt(now)
                    .status(ModificationStatus.AWAITING_REFUND).build());
            modificationRequestRepository.flush();
            Payment refund = paymentRepository.save(Payment.builder().orderId(o.getId()).modificationRequestId(sys.getId())
                    .direction(PaymentDirection.REFUND).purpose(PaymentPurpose.MODIFICATION_REFUND).method(PaymentMethod.OFFLINE_TRANSFER)
                    .amount(refundAmount).status(PaymentStatus.UNPAID).build());
            refundModId = sys.getId();
            refundPayId = refund.getId();
            t.add(OrderLogWriter.transition("MODIFICATION", sys.getId(), null, ModificationStatus.AWAITING_REFUND));
            t.add(OrderLogWriter.transition("PAYMENT", refund.getId(), null, PaymentStatus.UNPAID));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("modificationId", mod.getId().toString());
        payload.put("paymentId", sp.get().getId().toString());
        payload.put("totalAmountBefore", before);
        payload.put("totalAmountAfter", after);
        payload.put("systemRefundModificationId", refundModId == null ? null : refundModId.toString());
        payload.put("systemRefundPaymentId", refundPayId == null ? null : refundPayId.toString());
        payload.put("refundAmount", refundAmount);
        payload.put("transitions", t);
        orderLogWriter.write(locked, "SUPPLEMENT_EXPIRED", Actor.SYSTEM, payload);
        if (refundModId == null) {
            aggregateSupport.cancelIfNoEffectiveVisits(locked, mod);
        }
        notifier.modification(o, "OWNER", mod.getId(), "SUPPLEMENT_EXPIRED", Map.of());
        notifier.modification(o, "SITTER", mod.getId(), "SUPPLEMENT_EXPIRED", Map.of());
        if (refundModId != null) {
            notifier.modification(o, "SITTER", refundModId, "SYSTEM_REFUND_REQUIRED", Map.of("amount", refundAmount));
        }
        return true;
    }

    /** C7：殭屍行程關閉（SD-FULFILL closeZombieVisits，含通知）→ O9 → 重算基準 → VISITS_CLOSED_BY_SYSTEM。 */
    @Transactional
    public boolean zombieVisits(UUID orderId) {
        Optional<LockedOrder> lo = lockService.tryLockForCron(orderId);
        if (lo.isEmpty()) {
            return false;
        }
        LockedOrder locked = lo.get();
        List<UUID> closed = visitCommandService.closeZombieVisits(locked, clock.now(), settings.visitZombie());
        if (closed.isEmpty()) {
            return false;
        }
        lifecycleService.onVisitLeftPending(locked, closed.get(0), VisitExitCause.CLOSED_BY_SYSTEM, Actor.SYSTEM);
        lifecycleService.onVisitFinished(locked);
        Order o = locked.entity();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("visitIds", closed.stream().map(UUID::toString).toList());
        payload.put("completionBasisAt", o.getCompletionBasisAt() == null ? null : o.getCompletionBasisAt().toString());
        payload.put("transitions", closed.stream().map(v -> OrderLogWriter.transition("VISIT", v, "PENDING", "CLOSED_BY_SYSTEM")).toList());
        orderLogWriter.write(locked, "VISITS_CLOSED_BY_SYSTEM", Actor.SYSTEM, payload);
        return true;
    }

    /** C8 O12。 */
    @Transactional
    public boolean autoComplete(UUID orderId) {
        Optional<LockedOrder> lo = lockService.tryLockForCron(orderId);
        return lo.isPresent() && completionService.autoCompleteInTx(lo.get());
    }

    private static String taipei(OffsetDateTime t) {
        return t.atZoneSameInstant(OrderClock.TAIPEI).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }
}
