package com.petsitter.application.service.order;

import com.petsitter.application.dto.order.EvaluationResponse;
import com.petsitter.application.dto.order.OrderCard;
import com.petsitter.application.dto.order.OrderDetailResponse;
import com.petsitter.application.dto.order.PageResponse;
import com.petsitter.application.dto.order.PaymentView;
import com.petsitter.application.exception.OrderException;
import com.petsitter.application.port.PayoutAccountQuery;
import com.petsitter.application.port.PetQuery;
import com.petsitter.application.port.ServicePlanQuery;
import com.petsitter.application.port.ServicePlanQuery.PlanView;
import com.petsitter.application.port.SubscriptionQuery.SubscriptionStatus;
import com.petsitter.application.port.UserDisplayQuery;
import com.petsitter.application.service.fulfill.VisitQueryService;
import com.petsitter.application.service.fulfill.VisitView;
import com.petsitter.domain.model.order.ActorRole;
import com.petsitter.domain.model.order.InitiatedBy;
import com.petsitter.domain.model.order.ModificationContent;
import com.petsitter.domain.model.order.ModificationRequest;
import com.petsitter.domain.model.order.ModificationStatus;
import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.OrderAnswer;
import com.petsitter.domain.model.order.OrderItem;
import com.petsitter.domain.model.order.OrderItemPet;
import com.petsitter.domain.model.order.OrderLog;
import com.petsitter.domain.model.order.OrderPlanSnapshot;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.model.order.Payment;
import com.petsitter.domain.model.order.PaymentPurpose;
import com.petsitter.domain.model.order.PaymentStatus;
import com.petsitter.domain.model.order.PaymentSubmission;
import com.petsitter.domain.model.order.SubmissionStatus;
import com.petsitter.domain.model.order.VisitStatus;
import com.petsitter.domain.repository.order.ModificationRequestRepository;
import com.petsitter.domain.repository.order.OrderAnswerRepository;
import com.petsitter.domain.repository.order.OrderItemPetRepository;
import com.petsitter.domain.repository.order.OrderItemRepository;
import com.petsitter.domain.repository.order.OrderLogRepository;
import com.petsitter.domain.repository.order.OrderPlanSnapshotRepository;
import com.petsitter.domain.repository.order.OrderRepository;
import com.petsitter.domain.repository.order.PaymentRepository;
import com.petsitter.domain.repository.order.PaymentSubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 訂單唯讀端點 E2、E3、E4、E5（SD-ORDER §6.3）；E27 / E30 共用組裝。全部在唯讀交易內、同一個 serverTime。 */
@Service
@RequiredArgsConstructor
public class OrderDetailService {

    static final Set<OrderStatus> EVALUATING = EnumSet.of(OrderStatus.PENDING, OrderStatus.PENDING_PAYMENT);
    static final Set<OrderStatus> ACTIVE = EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.IN_PROGRESS, OrderStatus.DISPUTED);
    static final Set<OrderStatus> HISTORY = EnumSet.of(OrderStatus.COMPLETED, OrderStatus.CANCELLED);

    private final OrderCommandSupport support;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderItemPetRepository orderItemPetRepository;
    private final OrderPlanSnapshotRepository planSnapshotRepository;
    private final OrderAnswerRepository orderAnswerRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentSubmissionRepository submissionRepository;
    private final ModificationRequestRepository modificationRequestRepository;
    private final OrderLogRepository orderLogRepository;
    private final VisitQueryService visitQueryService;
    private final ServicePlanQuery servicePlanQuery;
    private final PetQuery petQuery;
    private final UserDisplayQuery userDisplayQuery;
    private final PayoutAccountQuery payoutAccountQuery;
    private final OrderQueryService orderQueryService;
    private final PaymentViews paymentViews;
    private final PlanPolicy planPolicy;
    private final OrderSettings settings;
    private final OrderClock clock;
    private final JsonMapper jsonMapper;

    // ------------------------------------------------------------------ E2

    public OrderDetailResponse detail(UUID orderId) {
        CurrentActor actor = CurrentActor.fromToken();
        return support.readOnly(() -> {
            Order order = support.loadOrder(orderId);
            ActorRole role = OrderAccess.partyRole(order, actor);
            return build(order, role, clock.now(), false);
        });
    }

    /** E2 / E27 共用組裝。{@code role}：OWNER / SITTER / ADMIN。 */
    OrderDetailResponse build(Order order, ActorRole role, OffsetDateTime now, boolean adminView) {
        List<OrderItem> items = OrderQueryService.sortItems(orderItemRepository.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId()));
        List<VisitView> visits = visitQueryService.listVisits(order.getId());
        List<VisitView> effective = visits.stream().filter(VisitView::effective).toList();
        List<ModificationRequest> mods = modificationRequestRepository.findByOrderIdOrderByCreatedAtDescIdDesc(order.getId());
        Optional<ModificationRequest> open = mods.stream().filter(ModificationRequest::isOpen).findFirst();
        SubscriptionStatus sub = planPolicy.status(order.getSitterId());
        List<OrderDetailResponse.Group> groups = groups(order, items, sub);

        UUID counterpartId = role == ActorRole.SITTER ? order.getOwnerId() : order.getSitterId();
        String counterpartName = role == ActorRole.SITTER
                ? userDisplayQuery.ownerDisplayNames(Set.of(counterpartId)).get(counterpartId)
                : userDisplayQuery.sitterDisplayNames(Set.of(counterpartId)).get(counterpartId);
        OrderDetailResponse.Counterpart counterpart = role == ActorRole.ADMIN ? null
                : new OrderDetailResponse.Counterpart(counterpartId, counterpartName);

        List<PaymentView> payments = paymentViews.list(order, role, adminView, now);
        if (!adminView) {
            payments = payments.stream().map(PaymentView::withoutSnapshot).toList();
        }
        int netPaid = paymentRepository.netPaid(order.getId());
        LocalDate last = effective.stream().map(VisitView::serviceDate).max(LocalDate::compareTo).orElse(null);
        boolean allFinished = !effective.isEmpty() && effective.stream().allMatch(VisitView::finished);
        OffsetDateTime disputeWindowEndsAt = order.getStatus() == OrderStatus.IN_PROGRESS && allFinished
                && open.isEmpty() && order.getCompletionBasisAt() != null
                ? order.getCompletionBasisAt().plus(settings.disputeWindow()) : null;
        OrderDetailResponse.CompletionHint hint = role == ActorRole.OWNER && order.getStatus() == OrderStatus.IN_PROGRESS
                ? completionHint(order, effective, open.isPresent(), now) : null;

        String tier = role == ActorRole.SITTER ? sub.tier().name() : null;
        String pricingMode = role == ActorRole.SITTER
                ? (planPolicy.adjustmentAllowed(sub) ? PlanPolicy.PricingMode.CUSTOM_ALLOWED : PlanPolicy.PricingMode.SYSTEM_ONLY).name()
                : null;
        OrderDetailResponse.Cancellation cancellation = order.getStatus() == OrderStatus.CANCELLED
                ? new OrderDetailResponse.Cancellation(name(order.getCancelledBy()), name(order.getCancelReasonCode()),
                order.getCancelReason(), order.getCancelledAt()) : null;

        List<String> actions = adminView ? adminActions(order) : allowedActions(order, role, effective, open, payments, hint, now);
        return new OrderDetailResponse(order.getId(), order.getStatus().name(), role.name(), counterpart,
                order.getFirstServiceDate(), last, order.getReviewExpiresAt(), order.getBaseAmount(),
                quote(order, mods, items), tier, pricingMode, groups,
                effective.stream().map(v -> new OrderDetailResponse.VisitItem(v.visitId(), v.serviceDate(), v.planId(),
                        v.status().name(), v.completedAt(), v.careLogSubmitted())).toList(),
                order.getQuestionnaireStatus().name(), name(order.getQuestionnaireExemptReason()), payments,
                open.map(m -> new OrderDetailResponse.OpenModification(m.getId(), m.getStatus().name(),
                        m.getInitiatedBy().name())).orElse(null),
                cancellation, order.getCompletedAt(), disputeWindowEndsAt, netPaid, now, hint, actions,
                null, null, null);
    }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }

    List<OrderDetailResponse.Group> groups(Order order, List<OrderItem> items, SubscriptionStatus sub) {
        Map<UUID, OrderPlanSnapshot> snaps = planSnapshotRepository.findByOrderId(order.getId()).stream()
                .collect(Collectors.toMap(OrderPlanSnapshot::getPlanId, Function.identity()));
        Map<UUID, PlanView> plans = servicePlanQuery.findForBooking(order.getSitterId(),
                        items.stream().map(OrderItem::getPlanId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(PlanView::planId, Function.identity()));
        List<OrderItemPet> bindings = items.isEmpty() ? List.of()
                : orderItemPetRepository.findByOrderItemIds(items.stream().map(OrderItem::getId).toList());
        Map<UUID, PetQuery.PetView> pets = petQuery.findForBooking(bindings.stream().map(OrderItemPet::getPetId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(PetQuery.PetView::petId, Function.identity()));
        Map<UUID, List<OrderItemPet>> byItem = bindings.stream().collect(Collectors.groupingBy(OrderItemPet::getOrderItemId));
        boolean range = PlanPolicy.bookableRangeApplies(sub);
        List<OrderDetailResponse.Group> out = new ArrayList<>();
        for (OrderItem it : items) {
            OrderPlanSnapshot snap = snaps.get(it.getPlanId());
            PlanView plan = plans.get(it.getPlanId());
            String title = snap != null ? snap.getPlanTitle() : plan != null ? plan.title() : null;
            Integer unit = snap != null ? snap.getUnitPrice() : it.getBookedUnitPrice();
            out.add(new OrderDetailResponse.Group(it.getId(), it.getSource().name(), it.getPlanId(), title, unit,
                    range && plan != null ? plan.bookableFrom() : null, range && plan != null ? plan.bookableTo() : null,
                    Arrays.asList(it.getServiceDates()), it.getDailyCount(),
                    byItem.getOrDefault(it.getId(), List.of()).stream().map(b -> pets.get(b.getPetId()))
                            .filter(Objects::nonNull)
                            .map(p -> new OrderDetailResponse.Pet(p.petId(), p.name(), p.type(), p.deleted())).toList()));
        }
        return out;
    }

    /** 報價明細三段（SD-ORDER §6.3 E2；PRD-006 BR-16）。 */
    OrderDetailResponse.Quote quote(Order order, List<ModificationRequest> mods, List<OrderItem> items) {
        if (order.getQuotedAt() == null) {
            return null;
        }
        int originalTotal = order.getBaseAmount() + nz(order.getSurchargeAmount()) - nz(order.getDiscountAmount());
        OrderDetailResponse.Original original = new OrderDetailResponse.Original(order.getBaseAmount(),
                order.getSurchargeAmount(), order.getDiscountAmount(), order.getPricingNotes(), originalTotal, order.getQuotedAt());
        Map<UUID, String> titles = planSnapshotRepository.findByOrderId(order.getId()).stream()
                .collect(Collectors.toMap(OrderPlanSnapshot::getPlanId, OrderPlanSnapshot::getPlanTitle));
        Map<UUID, LocalDate> visitDates = visitQueryService.listVisits(order.getId()).stream()
                .collect(Collectors.toMap(VisitView::visitId, VisitView::serviceDate));
        record Event(UUID modId, String kind, String type, OffsetDateTime at, ModificationContent content,
                     Integer explicitBefore, int after) {
        }
        List<Event> events = new ArrayList<>();
        Map<UUID, ModificationRequest> byId = mods.stream().collect(Collectors.toMap(ModificationRequest::getId, Function.identity()));
        for (ModificationRequest m : mods) {
            if (m.getStatus() == ModificationStatus.SETTLED && m.getInitiatedBy() != InitiatedBy.SYSTEM) {
                events.add(new Event(m.getId(), "SETTLED", m.getType().name(), m.getEndedAt(), m.getQuotedContent(), null,
                        m.getNewTotalAmount()));
            }
        }
        for (OrderLog log : orderLogRepository.findByOrderIdAndActionTypeOrderByCreatedAtAsc(order.getId(), "SUPPLEMENT_EXPIRED")) {
            Map<String, Object> p = log.getPayload();
            UUID modId = UUID.fromString(String.valueOf(p.get("modificationId")));
            ModificationRequest m = byId.get(modId);
            ModificationContent removed = m == null || m.getQuotedContent() == null ? ModificationContent.empty()
                    : new ModificationContent(m.getQuotedContent().removeVisitIds(), List.of());
            events.add(new Event(modId, "SUPPLEMENT_EXPIRED", m == null ? null : m.getType().name(), log.getCreatedAt(),
                    removed, ((Number) p.get("totalAmountBefore")).intValue(), ((Number) p.get("totalAmountAfter")).intValue()));
        }
        events.sort(Comparator.comparing(Event::at));
        List<OrderDetailResponse.Adjustment> adjustments = new ArrayList<>();
        int prev = originalTotal;
        for (Event e : events) {
            int before = e.explicitBefore() != null ? e.explicitBefore() : prev;
            int change = e.after() - before;
            prev = e.after();
            if (change == 0) {
                continue;
            }
            ModificationContent c = e.content() == null ? ModificationContent.empty() : e.content();
            adjustments.add(new OrderDetailResponse.Adjustment(e.modId(), e.kind(), e.type(), e.at(),
                    c.removeVisitIds().stream().map(visitDates::get).filter(Objects::nonNull).sorted().toList(),
                    "SETTLED".equals(e.kind()) ? c.addItems().stream().map(a -> new OrderDetailResponse.AddedItem(a.date(),
                            titles.get(a.planId()), a.dailyCount())).toList() : List.of(),
                    before, e.after(), change));
        }
        return new OrderDetailResponse.Quote(original, adjustments, order.getTotalAmount(), order.getQuotedAt());
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    /** completionHint（SD-ORDER §6.3 E2；PRD-009 §4.1）：i → v 取第一個成立者。 */
    OrderDetailResponse.CompletionHint completionHint(Order order, List<VisitView> effective, boolean openMod,
                                                      OffsetDateTime now) {
        if (openMod) {
            return new OrderDetailResponse.CompletionHint("MODIFICATION_IN_PROGRESS", null);
        }
        if (effective.isEmpty() || effective.stream().anyMatch(v -> !v.finished())) {
            return new OrderDetailResponse.CompletionHint("VISITS_NOT_FINISHED", null);
        }
        List<VisitView> unsubmitted = effective.stream()
                .filter(v -> v.status() == VisitStatus.DONE && !v.careLogSubmitted()).toList();
        if (unsubmitted.isEmpty()) {
            return null;
        }
        boolean expired = unsubmitted.stream().anyMatch(v -> !now.isBefore(v.completedAt().plus(settings.careLogEdit())));
        if (expired) {
            OffsetDateTime auto = order.getCompletionBasisAt() == null ? null
                    : order.getCompletionBasisAt().plus(settings.autoComplete());
            return new OrderDetailResponse.CompletionHint("CARE_LOG_EXPIRED", auto);
        }
        return new OrderDetailResponse.CompletionHint("CARE_LOG_PENDING", null);
    }

    List<String> allowedActions(Order order, ActorRole role, List<VisitView> effective, Optional<ModificationRequest> open,
                                List<PaymentView> payments, OrderDetailResponse.CompletionHint hint, OffsetDateTime now) {
        List<String> a = new ArrayList<>();
        OrderStatus s = order.getStatus();
        Optional<PaymentView> initial = payments.stream().filter(p -> PaymentPurpose.INITIAL.name().equals(p.purpose())).findFirst();
        boolean initialUnpaid = initial.map(p -> PaymentStatus.UNPAID.name().equals(p.status())).orElse(false);
        if (role == ActorRole.OWNER && (s == OrderStatus.PENDING || (s == OrderStatus.PENDING_PAYMENT && initialUnpaid))) {
            a.add("WITHDRAW");
        }
        if (role == ActorRole.SITTER && s == OrderStatus.PENDING) {
            a.add("DECLINE");
            if (now.isBefore(order.getReviewExpiresAt())) {
                a.add("QUOTE");
            }
        }
        if (role == ActorRole.SITTER && s == OrderStatus.PENDING_PAYMENT && initialUnpaid) {
            a.add("SITTER_CANCEL");
        }
        boolean anyPending = effective.stream().anyMatch(v -> v.status() == VisitStatus.PENDING);
        if ((s == OrderStatus.CONFIRMED || s == OrderStatus.IN_PROGRESS) && open.isEmpty() && anyPending) {
            a.add("REQUEST_MODIFICATION");
        }
        if (role == ActorRole.OWNER && s == OrderStatus.IN_PROGRESS && hint == null) {
            a.add("COMPLETE");
        }
        if (role == ActorRole.OWNER && s == OrderStatus.IN_PROGRESS && open.isEmpty() && order.getCompletionBasisAt() != null
                && !effective.isEmpty() && effective.stream().allMatch(VisitView::finished)
                && now.isBefore(order.getCompletionBasisAt().plus(settings.disputeWindow()))) {
            a.add("REPORT_SERVICE_ISSUE");
        }
        if ((s == OrderStatus.CONFIRMED || s == OrderStatus.IN_PROGRESS) && open.isPresent()
                && disputeAvailable(open.get(), now)) {
            a.add("PAYMENT_DISPUTE");
        }
        return a;
    }

    /** O14 可申報：AWAITING_REFUND；或補款 SUBMITTED 且自目前 submission 起已滿 72h。 */
    boolean disputeAvailable(ModificationRequest m, OffsetDateTime now) {
        if (m.getStatus() == ModificationStatus.AWAITING_REFUND) {
            return true;
        }
        if (m.getStatus() != ModificationStatus.AWAITING_SUPPLEMENT) {
            return false;
        }
        OffsetDateTime at = disputeAvailableAt(m);
        return at != null && !now.isBefore(at);
    }

    /** 補款 SUBMITTED 時 = 目前 submission 的 submitted_at + MODIFICATION_TIMEOUT_HOURS。 */
    OffsetDateTime disputeAvailableAt(ModificationRequest m) {
        Optional<Payment> p = paymentRepository.findByModificationRequestId(m.getId());
        if (p.isEmpty() || p.get().getStatus() != PaymentStatus.SUBMITTED) {
            return null;
        }
        return submissionRepository.findFirstByPaymentIdAndStatus(p.get().getId(), SubmissionStatus.SUBMITTED)
                .map(PaymentSubmission::getSubmittedAt).map(t -> t.plus(settings.modificationTimeout())).orElse(null);
    }

    static List<String> adminActions(Order order) {
        List<String> a = new ArrayList<>();
        if (order.getStatus() == OrderStatus.DISPUTED) {
            a.add("MEDIATE");
        }
        return a;
    }

    // ------------------------------------------------------------------ E3 / E4

    public PageResponse<OrderCard> list(boolean asOwner, String tab, String status, String month, Integer page, Integer size) {
        CurrentActor actor = CurrentActor.fromToken();
        Set<OrderStatus> statuses = switch (tab == null ? "" : tab) {
            case "EVALUATING" -> EVALUATING;
            case "ACTIVE" -> ACTIVE;
            case "HISTORY" -> HISTORY;
            default -> throw OrderException.validation("tab");
        };
        int p = page == null ? 0 : page;
        int sz = size == null ? 20 : size;
        if (p < 0) {
            throw OrderException.validation("page");
        }
        if (sz < 1 || sz > 50) {
            throw OrderException.validation("size");
        }
        boolean history = "HISTORY".equals(tab);
        if (!history && (status != null || month != null)) {
            throw OrderException.validation(status != null ? "status" : "month");
        }
        Set<OrderStatus> effective = statuses;
        if (status != null) {
            if (!"COMPLETED".equals(status) && !"CANCELLED".equals(status)) {
                throw OrderException.validation("status");
            }
            effective = EnumSet.of(OrderStatus.valueOf(status));
        }
        OffsetDateTime from = OffsetDateTime.parse("1970-01-01T00:00:00Z");
        OffsetDateTime to = OffsetDateTime.parse("9999-01-01T00:00:00Z");
        if (month != null) {
            YearMonth ym = parseMonth(month, "month");
            from = OrderClock.startOfMonth(ym);
            to = OrderClock.startOfMonth(ym.plusMonths(1));
        }
        Set<OrderStatus> fs = effective;
        OffsetDateTime ff = from;
        OffsetDateTime ft = to;
        return support.readOnly(() -> {
            Page<Order> orders;
            if (history) {
                orders = orderRepository.findHistory(actor.userId(), asOwner, fs, ff, ft, PageRequest.of(p, sz));
            } else {
                PageRequest pr = PageRequest.of(p, sz, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
                orders = asOwner ? orderRepository.findByOwnerIdAndStatusIn(actor.userId(), fs, pr)
                        : orderRepository.findBySitterIdAndStatusIn(actor.userId(), fs, pr);
            }
            return new PageResponse<>(cards(orders.getContent(), asOwner, history), p, sz, orders.getTotalElements());
        });
    }

    static YearMonth parseMonth(String month, String field) {
        if (month == null || !month.matches("^\\d{4}-\\d{2}$")) {
            throw OrderException.validation(field);
        }
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw OrderException.validation(field);
        }
    }

    List<OrderCard> cards(List<Order> orders, boolean asOwner, boolean history) {
        if (orders.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = orders.stream().map(Order::getId).collect(Collectors.toSet());
        Map<UUID, String> names = asOwner
                ? userDisplayQuery.sitterDisplayNames(orders.stream().map(Order::getSitterId).collect(Collectors.toSet()))
                : userDisplayQuery.ownerDisplayNames(orders.stream().map(Order::getOwnerId).collect(Collectors.toSet()));
        Map<UUID, List<Payment>> payments = paymentRepository.findByOrderIdIn(ids).stream()
                .collect(Collectors.groupingBy(Payment::getOrderId));
        Set<UUID> withOpenMod = modificationRequestRepository.findByOrderIdInAndStatusIn(ids, ModificationRequest.OPEN_STATUSES)
                .stream().map(ModificationRequest::getOrderId).collect(Collectors.toSet());
        Map<UUID, List<OrderItem>> items = orderItemRepository.findByOrderIdIn(ids).stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
        List<OrderCard> out = new ArrayList<>();
        for (Order o : orders) {
            List<Payment> ps = payments.getOrDefault(o.getId(), List.of());
            String initialStatus = ps.stream().filter(p -> p.getPurpose() == PaymentPurpose.INITIAL).findFirst()
                    .map(p -> p.getStatus().name()).orElse(null);
            int net = ps.stream().filter(p -> p.getStatus() == PaymentStatus.VERIFIED)
                    .mapToInt(p -> p.getDirection() == com.petsitter.domain.model.order.PaymentDirection.CHARGE ? p.getAmount() : -p.getAmount())
                    .sum();
            LocalDate last = lastServiceDate(o, items.getOrDefault(o.getId(), List.of()));
            out.add(new OrderCard(o.getId(), o.getStatus().name(), names.get(asOwner ? o.getSitterId() : o.getOwnerId()),
                    o.getFirstServiceDate(), last, o.getBaseAmount(), o.getTotalAmount(), withOpenMod.contains(o.getId()),
                    initialStatus, o.getCreatedAt(), history ? terminalAt(o) : null, history ? net : null));
        }
        return out;
    }

    /** 最後一個有效行程日；查行程（列表頁每批 ≤ 50 筆）。 */
    LocalDate lastServiceDate(Order o, List<OrderItem> items) {
        return visitQueryService.listVisits(o.getId()).stream().filter(VisitView::effective).map(VisitView::serviceDate)
                .max(LocalDate::compareTo)
                .orElseGet(() -> items.stream().flatMap(i -> Arrays.stream(i.getServiceDates())).max(LocalDate::compareTo)
                        .orElse(o.getFirstServiceDate()));
    }

    static OffsetDateTime terminalAt(Order o) {
        return o.getCompletedAt() != null ? o.getCompletedAt() : o.getCancelledAt();
    }

    // ------------------------------------------------------------------ E5

    public EvaluationResponse evaluation(UUID orderId) {
        CurrentActor actor = CurrentActor.fromToken();
        return support.readOnly(() -> {
            Order order = support.loadOrder(orderId);
            OrderAccess.requireSitter(order, actor);
            OffsetDateTime now = clock.now();
            SubscriptionStatus sub = planPolicy.status(order.getSitterId());
            List<OrderItem> items = OrderQueryService.sortItems(orderItemRepository.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId()));
            List<EvaluationResponse.Answer> answers = orderAnswerRepository.findByOrderIdOrderBySortOrderAsc(order.getId()).stream()
                    .map(this::answer).toList();
            String ownerName = userDisplayQuery.ownerDisplayNames(Set.of(order.getOwnerId())).get(order.getOwnerId());
            boolean quotaReached = orderQueryService.countConfirmedInMonth(order.getSitterId(), clock.monthTaipei())
                    >= PlanPolicy.quotaLimit(sub.tier());
            return new EvaluationResponse(order.getId(), order.getStatus().name(), order.getReviewExpiresAt(),
                    order.getBaseAmount(), new EvaluationResponse.Owner(ownerName),
                    orderQueryService.isRegularOwner(order.getOwnerId(), order.getSitterId()),
                    groups(order, items, sub),
                    new EvaluationResponse.Questionnaire(order.getQuestionnaireStatus().name(),
                            name(order.getQuestionnaireExemptReason()), answers),
                    new EvaluationResponse.Precheck(sub.locked(), quotaReached,
                            !payoutAccountQuery.isConfigured(order.getSitterId()),
                            !now.isBefore(order.getReviewExpiresAt()), planPolicy.adjustmentAllowed(sub)),
                    now);
        });
    }

    private EvaluationResponse.Answer answer(OrderAnswer a) {
        Object value = jsonMapper.readValue(a.getAnswerJson(), Object.class);
        return new EvaluationResponse.Answer(a.getQuestionId(), a.getQuestionText(), a.getQuestionType(), value);
    }

    Map<UUID, String> ownerNames(Set<UUID> ids) {
        return ids.isEmpty() ? new HashMap<>() : userDisplayQuery.ownerDisplayNames(new HashSet<>(ids));
    }
}
