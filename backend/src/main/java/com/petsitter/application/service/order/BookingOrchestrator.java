package com.petsitter.application.service.order;

import com.petsitter.application.common.idempotency.IdempotencyClaim;
import com.petsitter.application.common.idempotency.IdempotencyContext;
import com.petsitter.application.common.idempotency.IdempotencyService;
import com.petsitter.application.common.text.TextRules;
import com.petsitter.application.dto.order.CreateOrderRequest;
import com.petsitter.application.dto.order.CreateOrderResponse;
import com.petsitter.application.exception.OrderErrorCode;
import com.petsitter.application.exception.OrderException;
import com.petsitter.application.port.PetQuery;
import com.petsitter.application.port.PublicProfileQuery;
import com.petsitter.application.port.ServicePlanQuery;
import com.petsitter.application.port.ServicePlanQuery.PlanView;
import com.petsitter.application.port.SitterQuestionQuery.QuestionView;
import com.petsitter.application.port.SubscriptionQuery.SubscriptionStatus;
import com.petsitter.application.service.FavoriteSitterService;
import com.petsitter.application.service.fulfill.VisitCommandService;
import com.petsitter.application.service.fulfill.VisitSpec;
import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.OrderAnswer;
import com.petsitter.domain.model.order.OrderItem;
import com.petsitter.domain.model.order.OrderItemPet;
import com.petsitter.domain.model.order.OrderItemSource;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.model.order.QuestionnaireStatus;
import com.petsitter.domain.repository.order.OrderAnswerRepository;
import com.petsitter.domain.repository.order.OrderItemPetRepository;
import com.petsitter.domain.repository.order.OrderItemRepository;
import com.petsitter.domain.repository.order.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * E1 O1 送出預約（SD-ORDER §4.1、§6.3 E1；PRD-005 §4.4）。非交易的協調者：
 * G4 / G5 / G6 → 認領（交易 A）→ 交易 B（業務規則、建立、log、enqueue、冪等完成）→ commit 後我的最愛（另開短交易）。
 * 不以鎖做業務判斷（新建、不做容量 / 檔期鎖）；flush 後對自己的新列取鎖只為取得 {@link LockedOrder} 呼叫 SD-FULFILL 與 log。
 */
@Slf4j
@Service
public class BookingOrchestrator {

    static final int MAX_GROUPS = 10;
    static final int MAX_DATES = 60;
    static final int MAX_DAILY = 3;
    static final int SHORT_ANSWER_MAX = 200;
    static final int LONG_ANSWER_MAX = 1000;

    private final PublicProfileQuery publicProfileQuery;
    private final PetQuery petQuery;
    private final ServicePlanQuery servicePlanQuery;
    private final PlanPolicy planPolicy;
    private final BookingRules bookingRules;
    private final OrderRequestValidator validator;
    private final IdempotencyService idempotencyService;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderItemPetRepository orderItemPetRepository;
    private final OrderAnswerRepository orderAnswerRepository;
    private final OrderLockService orderLockService;
    private final VisitCommandService visitCommandService;
    private final OrderLogWriter orderLogWriter;
    private final OrderNotifier notifier;
    private final FavoriteSitterService favoriteSitterService;
    private final OrderSettings settings;
    private final OrderClock clock;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate tx;

    public BookingOrchestrator(PublicProfileQuery publicProfileQuery, PetQuery petQuery, ServicePlanQuery servicePlanQuery,
                               PlanPolicy planPolicy, BookingRules bookingRules, OrderRequestValidator validator,
                               IdempotencyService idempotencyService, OrderRepository orderRepository,
                               OrderItemRepository orderItemRepository, OrderItemPetRepository orderItemPetRepository,
                               OrderAnswerRepository orderAnswerRepository, OrderLockService orderLockService,
                               VisitCommandService visitCommandService, OrderLogWriter orderLogWriter,
                               OrderNotifier notifier, FavoriteSitterService favoriteSitterService,
                               OrderSettings settings, OrderClock clock, JsonMapper jsonMapper,
                               PlatformTransactionManager transactionManager) {
        this.publicProfileQuery = publicProfileQuery;
        this.petQuery = petQuery;
        this.servicePlanQuery = servicePlanQuery;
        this.planPolicy = planPolicy;
        this.bookingRules = bookingRules;
        this.validator = validator;
        this.idempotencyService = idempotencyService;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderItemPetRepository = orderItemPetRepository;
        this.orderAnswerRepository = orderAnswerRepository;
        this.orderLockService = orderLockService;
        this.visitCommandService = visitCommandService;
        this.orderLogWriter = orderLogWriter;
        this.notifier = notifier;
        this.favoriteSitterService = favoriteSitterService;
        this.settings = settings;
        this.clock = clock;
        this.jsonMapper = jsonMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    record ParsedGroup(int index, UUID planId, List<LocalDate> dates, int dailyCount, List<UUID> petIds) {
    }

    public CreateOrderResponse create(CreateOrderRequest req) {
        CurrentActor actor = CurrentActor.fromToken();
        if (req == null) {
            throw OrderException.validation("body");
        }
        // G4：保母存在、毛孩存在（已刪除 → 404）；G5：毛孩歸屬（403 先於 400，PRD-005 AC-2.5）
        UUID sitterId = validator.uuid(req.sitterId(), "sitterId");
        if (!publicProfileQuery.sitterExists(sitterId)) {
            throw OrderException.notFound();
        }
        Set<UUID> petIds = parseablePetIds(req);
        Map<UUID, PetQuery.PetView> pets = petQuery.findForBooking(petIds).stream()
                .collect(Collectors.toMap(PetQuery.PetView::petId, Function.identity()));
        for (UUID id : petIds) {
            PetQuery.PetView p = pets.get(id);
            if (p == null || p.deleted()) {
                throw OrderException.notFound();
            }
        }
        for (PetQuery.PetView p : pets.values()) {
            if (!p.ownerId().equals(actor.userId())) {
                throw OrderException.forbidden();
            }
        }
        // G6
        IdempotencyContext ctx = validator.requireIdempotencyKey();
        List<ParsedGroup> groups = parseGroups(req.groups());
        Map<UUID, Object> answers = parseAnswers(req.answers());
        int expected = validator.amount(req.expectedAmount(), "expectedAmount", 0, Integer.MAX_VALUE, true, 0);
        // G7
        IdempotencyClaim claim = idempotencyService.claim(ctx);
        CreateOrderResponse response = tx.execute(status -> createInTx(actor, sitterId, groups, answers, expected, claim));
        // commit 後：我的最愛（獨立短交易；失敗只記 log，PRD-005 BR-14）
        try {
            favoriteSitterService.addIfAbsent(actor.userId(), sitterId);
        } catch (RuntimeException e) {
            log.warn("favorite addIfAbsent failed for order {}", response.orderId(), e);
        }
        return response;
    }

    private CreateOrderResponse createInTx(CurrentActor actor, UUID sitterId, List<ParsedGroup> groups,
                                           Map<UUID, Object> answers, int expected, IdempotencyClaim claim) {
        OffsetDateTime now = clock.now();
        LocalDate today = clock.todayTaipei();
        if (sitterId.equals(actor.userId())) {
            throw new OrderException(OrderErrorCode.SELF_BOOKING_NOT_ALLOWED);
        }
        SubscriptionStatus sub = planPolicy.status(sitterId);
        BookingRules.Gate gate = bookingRules.evaluate(sitterId, actor.userId(), sub);
        if (gate.bookability() == BookingRules.Bookability.NOT_VERIFIED) {
            throw new OrderException(OrderErrorCode.SITTER_NOT_BOOKABLE, Map.of("reason", "NOT_VERIFIED"));
        }
        if (gate.bookability() == BookingRules.Bookability.UNAVAILABLE) {
            throw new OrderException(OrderErrorCode.SITTER_NOT_BOOKABLE, Map.of("reason", "UNAVAILABLE"));
        }
        if (gate.quotaReached()) {
            throw new OrderException(OrderErrorCode.MONTHLY_QUOTA_REACHED);
        }
        // SD-ORDER §6.3 E1（NFR-003 CQ-43、PRD-005 BR-26 §4.4 步驟 3.8.3）：每月額度之後；不加鎖單純計數
        // （同一飼主多裝置並行可能短暫超過，使用者 2026-11-22 決定不處理）；只計 PENDING；不依方案區分
        int pendingMax = settings.getOwnerPendingPerSitterMax();
        if (orderRepository.countByOwnerIdAndSitterIdAndStatus(actor.userId(), sitterId, OrderStatus.PENDING) >= pendingMax) {
            throw new OrderException(OrderErrorCode.PENDING_ORDER_LIMIT_REACHED, Map.of("limit", pendingMax));
        }
        Set<UUID> planIds = groups.stream().map(ParsedGroup::planId).collect(Collectors.toSet());
        Map<UUID, PlanView> plans = servicePlanQuery.findForBooking(sitterId, planIds).stream()
                .collect(Collectors.toMap(PlanView::planId, Function.identity()));
        for (ParsedGroup g : groups) {
            if (!BookingRules.planBookable(plans.get(g.planId()), sitterId, gate.gatekeeper())) {
                throw new OrderException(OrderErrorCode.PLAN_NOT_BOOKABLE);
            }
        }
        TreeSet<LocalDate> outOfRange = new TreeSet<>();
        for (ParsedGroup g : groups) {
            for (LocalDate d : g.dates()) {
                if (d.isBefore(today) || d.isAfter(today.plusDays(365))) {
                    outOfRange.add(d);
                }
            }
        }
        if (!outOfRange.isEmpty()) {
            throw invalidDates(outOfRange, "DATE_OUT_OF_RANGE");
        }
        if (PlanPolicy.bookableRangeApplies(sub)) {
            TreeSet<LocalDate> notInPlan = new TreeSet<>();
            for (ParsedGroup g : groups) {
                PlanView p = plans.get(g.planId());
                g.dates().stream().filter(d -> !p.inRange(d)).forEach(notInPlan::add);
            }
            if (!notInPlan.isEmpty()) {
                throw invalidDates(notInPlan, "PLAN_NOT_IN_RANGE");
            }
        }
        BookingRules.QuestionnaireDecision q = bookingRules.questionnaire(actor.userId(), sitterId);
        Map<UUID, Object> snapshot = q.required() ? validateAnswers(q.questions(), answers) : Map.of();
        long base = 0;
        for (ParsedGroup g : groups) {
            base += (long) g.dates().size() * g.dailyCount() * plans.get(g.planId()).unitPrice();
        }
        if (base > settings.getMaxAmount()) {
            throw OrderException.validation("baseAmount", Map.of("max", settings.getMaxAmount()));
        }
        if (base != expected) {
            throw new OrderException(OrderErrorCode.PRICE_CHANGED, Map.of("baseAmount", (int) base));
        }
        if (sub.locked()) {
            throw new OrderException(OrderErrorCode.SUBSCRIPTION_LOCKED);
        }

        LocalDate first = groups.stream().flatMap(g -> g.dates().stream()).min(LocalDate::compareTo).orElseThrow();
        OffsetDateTime reviewExpiresAt = OrderClock.max(OrderClock.startOfDay(first), now.plus(settings.urgentWindow()));
        Order order = Order.builder()
                .ownerId(actor.userId())
                .sitterId(sitterId)
                .status(OrderStatus.PENDING)
                .baseAmount((int) base)
                .reviewExpiresAt(reviewExpiresAt)
                .firstServiceDate(first)
                .questionnaireStatus(q.required() ? QuestionnaireStatus.COMPLETED : QuestionnaireStatus.NOT_REQUIRED)
                .questionnaireExemptReason(q.exemptReason())
                .build();
        orderRepository.saveAndFlush(order);
        LockedOrder locked = orderLockService.lockForTransition(order.getId());

        List<VisitSpec> specs = new ArrayList<>();
        for (ParsedGroup g : groups) {
            List<LocalDate> sorted = g.dates().stream().sorted().toList();
            OrderItem item = orderItemRepository.save(OrderItem.builder()
                    .orderId(order.getId())
                    .source(OrderItemSource.BOOKING)
                    .groupIndex((short) g.index())
                    .planId(g.planId())
                    .serviceDates(sorted.toArray(LocalDate[]::new))
                    .dailyCount((short) g.dailyCount())
                    .bookedUnitPrice(plans.get(g.planId()).unitPrice())
                    .createdAt(now)
                    .build());
            for (UUID petId : g.petIds()) {
                orderItemPetRepository.save(OrderItemPet.of(item.getId(), petId));
            }
            for (LocalDate d : sorted) {
                for (int seq = 1; seq <= g.dailyCount(); seq++) {
                    specs.add(new VisitSpec(item.getId(), g.planId(), plans.get(g.planId()).title(), d, seq));
                }
            }
        }
        if (q.required()) {
            for (QuestionView question : q.questions()) {
                orderAnswerRepository.save(OrderAnswer.builder()
                        .orderId(order.getId())
                        .questionId(question.questionId())
                        .sortOrder((short) question.sortOrder())
                        .questionText(question.text())
                        .questionType(question.type())
                        .answerJson(jsonMapper.writeValueAsString(snapshot.get(question.questionId())))
                        .createdAt(now)
                        .build());
            }
        }
        visitCommandService.createVisits(locked, specs);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("baseAmount", (int) base);
        payload.put("groupCount", groups.size());
        payload.put("visitCount", specs.size());
        payload.put("questionnaireStatus", order.getQuestionnaireStatus().name());
        payload.put("questionnaireExemptReason", q.exemptReason() == null ? null : q.exemptReason().name());
        payload.put("reviewExpiresAt", reviewExpiresAt.toString());
        payload.put("transitions", List.of(OrderLogWriter.transition("ORDER", order.getId(), null, OrderStatus.PENDING)));
        orderLogWriter.write(locked, "ORDER_CREATED", Actor.owner(actor.userId()), payload);
        notifier.sitter(order, "NEW_BOOKING_REQUEST", false, Map.of("firstServiceDate", first.toString()));

        CreateOrderResponse response = new CreateOrderResponse(order.getId(), OrderStatus.PENDING.name(), (int) base,
                reviewExpiresAt, order.getQuestionnaireStatus().name());
        idempotencyService.complete(claim, 201, response);
        return response;
    }

    private static OrderException invalidDates(Set<LocalDate> dates, String reason) {
        return new OrderException(OrderErrorCode.INVALID_VISIT_DATE,
                Map.of("dates", dates.stream().map(LocalDate::toString).toList(), "reason", reason));
    }

    // ------------------------------------------------------------------ G6 格式（BR-17、BR-18、BR-22）

    private Set<UUID> parseablePetIds(CreateOrderRequest req) {
        Set<UUID> ids = new LinkedHashSet<>();
        if (req.groups() == null) {
            return ids;
        }
        for (CreateOrderRequest.Group g : req.groups()) {
            if (g == null || g.petIds() == null) {
                continue;
            }
            for (String s : g.petIds()) {
                try {
                    if (s != null) {
                        ids.add(UUID.fromString(s));
                    }
                } catch (IllegalArgumentException ignored) {
                    // 格式錯誤留給 G6
                }
            }
        }
        return ids;
    }

    private List<ParsedGroup> parseGroups(List<CreateOrderRequest.Group> raw) {
        if (raw == null || raw.isEmpty() || raw.size() > MAX_GROUPS) {
            throw OrderException.validation("groups", Map.of("max", MAX_GROUPS));
        }
        List<ParsedGroup> out = new ArrayList<>();
        Map<UUID, Set<LocalDate>> datesByPlan = new HashMap<>();
        int totalDates = 0;
        for (int i = 0; i < raw.size(); i++) {
            CreateOrderRequest.Group g = raw.get(i);
            Map<String, Object> at = Map.of("groupIndex", i);
            if (g == null) {
                throw OrderException.validation("groups", at);
            }
            UUID planId = parseUuid(g.planId(), "planId", at);
            if (g.dates() == null || g.dates().isEmpty()) {
                throw OrderException.validation("dates", at);
            }
            List<LocalDate> dates = new ArrayList<>();
            Set<LocalDate> seen = new HashSet<>();
            for (String s : g.dates()) {
                LocalDate d;
                try {
                    d = validator.date(s, "dates");
                } catch (OrderException e) {
                    throw OrderException.validation("dates", at);
                }
                if (!seen.add(d)) {
                    throw OrderException.validation("dates", at);
                }
                dates.add(d);
            }
            int daily;
            try {
                daily = validator.amount(g.dailyCount(), "dailyCount", 1, MAX_DAILY, true, 0);
            } catch (OrderException e) {
                throw OrderException.validation("dailyCount", at);
            }
            if (g.petIds() == null || g.petIds().isEmpty()) {
                throw OrderException.validation("petIds", at);
            }
            List<UUID> pets = new ArrayList<>();
            for (String s : g.petIds()) {
                UUID id = parseUuid(s, "petIds", at);
                if (!pets.contains(id)) {
                    pets.add(id);
                }
            }
            Set<LocalDate> planDates = datesByPlan.computeIfAbsent(planId, k -> new HashSet<>());
            for (LocalDate d : dates) {
                if (!planDates.add(d)) {
                    throw OrderException.validation("dates", at);
                }
            }
            totalDates += dates.size();
            out.add(new ParsedGroup(i, planId, dates, daily, pets));
        }
        if (totalDates > MAX_DATES) {
            throw OrderException.validation("dates", Map.of("max", MAX_DATES));
        }
        return out;
    }

    private static UUID parseUuid(String s, String field, Map<String, Object> at) {
        try {
            if (s == null) {
                throw new IllegalArgumentException();
            }
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw OrderException.validation(field, at);
        }
    }

    private Map<UUID, Object> parseAnswers(List<CreateOrderRequest.Answer> raw) {
        Map<UUID, Object> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        for (CreateOrderRequest.Answer a : raw) {
            if (a == null) {
                throw OrderException.validation("answers");
            }
            UUID qid;
            try {
                qid = UUID.fromString(a.questionId());
            } catch (RuntimeException e) {
                throw OrderException.validation("answers");
            }
            Object v = a.value();
            boolean badShape = (v != null && !(v instanceof String) && !(v instanceof List<?>))
                    || (v instanceof List<?> list && list.stream().anyMatch(x -> !(x instanceof String)));
            if (badShape || out.containsKey(qid)) {
                throw OrderException.validation("answers", Map.of("questionId", qid.toString()));
            }
            out.put(qid, v);
        }
        return out;
    }

    // ------------------------------------------------------------------ 問卷（BR-22、BR-24）

    /** 回傳每題快照值：選擇題 List&lt;String&gt;、文字題 String；未答不放（存 JSON null）。 */
    private Map<UUID, Object> validateAnswers(List<QuestionView> questions, Map<UUID, Object> answers) {
        Map<UUID, Object> snapshot = new HashMap<>();
        boolean changed = false;
        for (QuestionView q : questions) {
            Object raw = answers.get(q.questionId());
            String type = q.type();
            if (raw == null || isEmptyAnswer(raw)) {
                if (q.required()) {
                    changed = true;
                }
                continue;
            }
            if ("RADIO".equals(type)) {
                if (!(raw instanceof String s) || !q.options().contains(TextRules.strip(s))) {
                    changed = true;
                    continue;
                }
                snapshot.put(q.questionId(), List.of(TextRules.strip(s)));
            } else if ("CHECKBOX".equals(type)) {
                if (!(raw instanceof List<?> list)) {
                    changed = true;
                    continue;
                }
                List<String> picked = new ArrayList<>();
                for (Object o : list) {
                    String s = TextRules.strip((String) o);
                    if (!s.isEmpty()) {
                        picked.add(s);
                    }
                }
                if (!q.options().containsAll(picked)) {
                    changed = true;
                    continue;
                }
                snapshot.put(q.questionId(), picked);
            } else {
                if (!(raw instanceof String s)) {
                    changed = true;
                    continue;
                }
                snapshot.put(q.questionId(), TextRules.strip(s));
            }
        }
        if (changed) {
            throw new OrderException(OrderErrorCode.QUESTIONNAIRE_CHANGED,
                    Map.of("questions", BookingRules.toQuestions(questions)));
        }
        for (QuestionView q : questions) {
            if (snapshot.get(q.questionId()) instanceof String text) {
                int max = "TEXTAREA".equals(q.type()) ? LONG_ANSWER_MAX : SHORT_ANSWER_MAX;
                if (TextRules.codePointLength(text) > max) {
                    throw OrderException.validation("answers", Map.of("questionId", q.questionId().toString()));
                }
            }
        }
        return snapshot;
    }

    private static boolean isEmptyAnswer(Object raw) {
        if (raw instanceof String s) {
            return TextRules.isBlank(s);
        }
        if (raw instanceof List<?> l) {
            return l.isEmpty() || l.stream().allMatch(x -> x instanceof String s && TextRules.isBlank(s));
        }
        return false;
    }
}
