package com.petsitter.application.service.order;

import com.petsitter.application.common.idempotency.IdempotencyClaim;
import com.petsitter.application.common.idempotency.IdempotencyContext;
import com.petsitter.application.dto.order.OrderCommandRequests;
import com.petsitter.application.dto.order.OrderCommandResponses;
import com.petsitter.application.exception.OrderErrorCode;
import com.petsitter.application.exception.OrderException;
import com.petsitter.application.port.PayoutAccountQuery;
import com.petsitter.application.port.ServicePlanQuery;
import com.petsitter.application.port.ServicePlanQuery.PlanView;
import com.petsitter.application.port.SubscriptionQuery.SubscriptionStatus;
import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.OrderItem;
import com.petsitter.domain.model.order.OrderPlanSnapshot;
import com.petsitter.domain.model.order.OrderSnapshot;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.model.order.Payment;
import com.petsitter.domain.model.order.PaymentDirection;
import com.petsitter.domain.model.order.PaymentMethod;
import com.petsitter.domain.model.order.PaymentPurpose;
import com.petsitter.domain.model.order.PaymentStatus;
import com.petsitter.domain.repository.order.OrderItemRepository;
import com.petsitter.domain.repository.order.OrderPlanSnapshotRepository;
import com.petsitter.domain.repository.order.OrderSnapshotRepository;
import com.petsitter.domain.repository.order.PaymentRepository;
import com.petsitter.infrastructure.security.gating.PlanTier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** E6 O2 報價（SD-ORDER §4.2、§6.3 E6；PRD-006 §4.3）。 */
@Service
@RequiredArgsConstructor
public class QuoteService {

    private final OrderCommandSupport support;
    private final OrderRequestValidator validator;
    private final OrderItemRepository orderItemRepository;
    private final OrderPlanSnapshotRepository planSnapshotRepository;
    private final OrderSnapshotRepository orderSnapshotRepository;
    private final PaymentRepository paymentRepository;
    private final ServicePlanQuery servicePlanQuery;
    private final PayoutAccountQuery payoutAccountQuery;
    private final PayoutSnapshotCipher cipher;
    private final PlanPolicy planPolicy;
    private final OrderQueryService orderQueryService;
    private final OrderLogWriter orderLogWriter;
    private final OrderNotifier notifier;
    private final OrderSettings settings;
    private final OrderClock clock;

    /** 訂閱等級 → 媒體額度（baseline §2.1：FREE 僅文字、BASIC 5 照片、PRO 10 + 2 影片、ULTIMATE 25 + 5）。 */
    static int maxPhotos(PlanTier t) {
        return switch (t) {
            case FREE -> 0;
            case BASIC -> 5;
            case PRO -> 10;
            case ULTIMATE -> 25;
        };
    }

    static int maxVideos(PlanTier t) {
        return switch (t) {
            case PRO -> 2;
            case ULTIMATE -> 5;
            default -> 0;
        };
    }

    public OrderCommandResponses.Quote quote(UUID orderId, OrderCommandRequests.Quote req) {
        CurrentActor actor = CurrentActor.fromToken();
        Order order = support.loadOrder(orderId);
        OrderAccess.requireSitter(order, actor);
        // G6
        IdempotencyContext ctx = validator.requireIdempotencyKey();
        if (req == null) {
            throw OrderException.validation("expectedAmount");
        }
        int expected = validator.amount(req.expectedAmount(), "expectedAmount", 0, Integer.MAX_VALUE, true, 0);
        int surcharge = validator.amount(req.surchargeAmount(), "surchargeAmount", 0, settings.getMaxAmount(), false, 0);
        int discount = validator.amount(req.discountAmount(), "discountAmount", 0, settings.getMaxAmount(), false, 0);
        String notes = surcharge + discount > 0
                ? validator.requiredText(req.pricingNotes(), "pricingNotes", 200)
                : validator.optionalText(req.pricingNotes(), "pricingNotes", 200);
        if (surcharge + discount == 0) {
            notes = null;
        }
        String pricingNotes = notes;
        IdempotencyClaim claim = support.claim(ctx);
        return support.inLockedTx(orderId, locked -> doQuote(locked, actor, expected, surcharge, discount, pricingNotes, claim));
    }

    private OrderCommandResponses.Quote doQuote(LockedOrder locked, CurrentActor actor, int expected, int surcharge,
                                                int discount, String notes, IdempotencyClaim claim) {
        Order order = locked.entity();
        OffsetDateTime now = clock.now();
        if (order.getStatus() != OrderStatus.PENDING) {
            throw OrderException.stateConflict(order.getStatus());
        }
        if (!now.isBefore(order.getReviewExpiresAt())) {
            throw OrderException.deadline("REVIEW_EXPIRES_AT", order.getReviewExpiresAt());
        }
        if (order.getFirstServiceDate().isBefore(clock.todayTaipei())) {
            throw new OrderException(OrderErrorCode.FIRST_VISIT_DATE_PASSED);
        }
        SubscriptionStatus sub = planPolicy.status(order.getSitterId());
        if (orderQueryService.countConfirmedInMonth(order.getSitterId(), clock.monthTaipei()) >= PlanPolicy.quotaLimit(sub.tier())) {
            throw new OrderException(OrderErrorCode.MONTHLY_QUOTA_REACHED);
        }
        if (!payoutAccountQuery.isConfigured(order.getSitterId())) {
            throw new OrderException(OrderErrorCode.PAYOUT_ACCOUNT_REQUIRED);
        }
        // 以當下方案單價重算 base_amount（停用方案仍以其單價，PRD-006 BR-7）
        List<OrderItem> items = orderItemRepository.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId());
        Set<UUID> planIds = items.stream().map(OrderItem::getPlanId).collect(Collectors.toSet());
        Map<UUID, PlanView> plans = servicePlanQuery.findForBooking(order.getSitterId(), planIds).stream()
                .collect(Collectors.toMap(PlanView::planId, Function.identity()));
        long base = 0;
        for (OrderItem it : items) {
            PlanView p = plans.get(it.getPlanId());
            int price = p == null ? it.getBookedUnitPrice() : p.unitPrice();
            base += (long) it.getServiceDates().length * it.getDailyCount() * price;
        }
        long total = base + surcharge - discount;
        if (base != expected) {
            throw new OrderException(OrderErrorCode.PRICE_CHANGED, Map.of("baseAmount", base, "totalAmount", total));
        }
        if (total < 1) {
            throw new OrderException(OrderErrorCode.INVALID_QUOTE_AMOUNT);
        }
        if (total > settings.getMaxAmount() || base > settings.getMaxAmount()) {
            throw OrderException.validation("totalAmount", Map.of("max", settings.getMaxAmount()));
        }
        if (surcharge + discount > 0 && !planPolicy.adjustmentAllowed(sub)) {
            throw new OrderException(OrderErrorCode.PLAN_REQUIRED);
        }
        if (sub.locked()) {
            throw new OrderException(OrderErrorCode.SUBSCRIPTION_LOCKED);
        }
        PayoutAccountQuery.PayoutAccountSnapshot account = payoutAccountQuery.getSnapshot(order.getSitterId())
                .orElseThrow(() -> new OrderException(OrderErrorCode.PAYOUT_ACCOUNT_REQUIRED));

        order.setBaseAmount((int) base);
        order.setSurchargeAmount(surcharge);
        order.setDiscountAmount(discount);
        order.setPricingNotes(notes);
        order.setTotalAmount((int) total);
        order.setQuotedAt(now);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        for (UUID planId : planIds) {
            PlanView p = plans.get(planId);
            OrderItem any = items.stream().filter(i -> i.getPlanId().equals(planId)).findFirst().orElseThrow();
            planSnapshotRepository.save(OrderPlanSnapshot.builder()
                    .id(new OrderPlanSnapshot.Key(order.getId(), planId))
                    .planTitle(p == null ? "方案" : p.title())
                    .unitPrice(p == null ? any.getBookedUnitPrice() : p.unitPrice())
                    .snapshottedAt(now)
                    .build());
        }
        orderSnapshotRepository.save(OrderSnapshot.builder()
                .orderId(order.getId())
                .planTier(sub.tier().name())
                .maxPhotos(maxPhotos(sub.tier()))
                .maxVideos(maxVideos(sub.tier()))
                .mediaRetentionDays(sub.tier().getMediaRetentionDays())
                .build());

        OffsetDateTime firstDay = OrderClock.startOfDay(order.getFirstServiceDate());
        OffsetDateTime dueAt = OrderClock.max(firstDay.minusHours(24), now.plus(settings.urgentWindow()));
        OffsetDateTime escalateAt = OrderClock.max(firstDay, dueAt).plus(settings.urgentWindow());
        OffsetDateTime reminderPoint = firstDay.minus(settings.paymentReminderLead());
        boolean reminderMerged = !now.isBefore(reminderPoint);
        Payment payment = paymentRepository.save(Payment.builder()
                .orderId(order.getId())
                .direction(PaymentDirection.CHARGE)
                .purpose(PaymentPurpose.INITIAL)
                .method(PaymentMethod.OFFLINE_TRANSFER)
                .amount((int) total)
                .status(PaymentStatus.UNPAID)
                .dueAt(dueAt)
                .escalateAt(escalateAt)
                .remindAt(reminderMerged ? null : reminderPoint)
                .payeeAccountSnapshot(cipher.encrypt(account))
                .payeeAccountKeyVersion(PayoutSnapshotCipher.KEY_VERSION)
                .createdBy(actor.userId())
                .build());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("baseAmount", (int) base);
        payload.put("surchargeAmount", surcharge);
        payload.put("discountAmount", discount);
        payload.put("totalAmount", (int) total);
        payload.put("pricingNotes", notes);
        payload.put("paymentId", payment.getId().toString());
        payload.put("dueAt", dueAt.toString());
        payload.put("escalateAt", escalateAt.toString());
        payload.put("reminderMerged", reminderMerged);
        payload.put("transitions", List.of(
                OrderLogWriter.transition("ORDER", order.getId(), OrderStatus.PENDING, OrderStatus.PENDING_PAYMENT),
                OrderLogWriter.transition("PAYMENT", payment.getId(), null, PaymentStatus.UNPAID)));
        orderLogWriter.write(locked, "ORDER_QUOTED", Actor.sitter(actor.userId()), payload);
        notifier.owner(order, reminderMerged ? "QUOTE_SENT_WITH_PAYMENT_REMINDER" : "QUOTE_SENT", reminderMerged,
                Map.of("totalAmount", (int) total, "dueAt", dueAt.atZoneSameInstant(OrderClock.TAIPEI)
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))));

        OrderCommandResponses.Quote response = new OrderCommandResponses.Quote(order.getId(),
                OrderStatus.PENDING_PAYMENT.name(), (int) base, surcharge, discount, (int) total, now,
                new OrderCommandResponses.PaymentRef(payment.getId(), (int) total, dueAt, escalateAt));
        support.complete(claim, 200, response);
        return response;
    }
}
