package com.petsitter.application.service.fulfill;

import com.petsitter.application.dto.fulfill.VisitResponses;
import com.petsitter.application.exception.FulfillException;
import com.petsitter.application.service.order.CurrentActor;
import com.petsitter.application.service.order.OrderClock;
import com.petsitter.application.service.order.OrderQueryService;
import com.petsitter.domain.model.fulfill.Visit;
import com.petsitter.domain.model.fulfill.VisitChecklistItem;
import com.petsitter.domain.model.fulfill.VisitServiceReport;
import com.petsitter.domain.model.order.ActorRole;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.model.order.VisitStatus;
import com.petsitter.domain.repository.fulfill.VisitChecklistItemRepository;
import com.petsitter.domain.repository.fulfill.VisitRef;
import com.petsitter.domain.repository.fulfill.VisitRepository;
import com.petsitter.domain.repository.fulfill.VisitServiceReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 行程與檢核項讀取 F1、F2、F6、F7、F8（SD-FULFILL §6.3）。唯讀；行程 / 日誌狀態 / 檢核項各一次查詢、
 * {@code getOrderContexts} 一次（儀表板依 200 筆分批），不 N+1。{@code allowedActions} 等提示以注入 Clock 計算。
 */
@Service
@RequiredArgsConstructor
public class VisitReadService {

    private static final Set<OrderStatus> DASHBOARD_ORDER = EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.IN_PROGRESS,
            OrderStatus.DISPUTED, OrderStatus.COMPLETED);

    private final FulfillGuard guard;
    private final FulfillTx tx;
    private final VisitRepository visitRepository;
    private final VisitServiceReportRepository reportRepository;
    private final VisitChecklistItemRepository checklistRepository;
    private final OrderQueryService orderQueryService;
    private final VisitNameResolver nameResolver;
    private final VisitRules rules;
    private final OrderClock clock;

    // ------------------------------------------------------------------ F1

    public VisitResponses.VisitList list(UUID orderId) {
        CurrentActor actor = CurrentActor.fromToken();
        OrderQueryService.OrderParticipants p = guard.order(orderId);
        ActorRole role = guard.readerRole(orderId, p.ownerId(), p.sitterId(), actor);
        OffsetDateTime now = clock.now();
        return tx.readOnly(() -> {
            OrderQueryService.OrderContextView ctx = context(orderId);
            List<Visit> visits = new ArrayList<>(visitRepository.findByOrderIdOrderByServiceDateAscSequenceInDayAsc(orderId));
            Map<UUID, Integer> itemOrder = itemOrder(ctx);
            visits.sort(Comparator.comparing((Visit v) -> itemOrder.getOrDefault(v.getOrderItemId(), Integer.MAX_VALUE))
                    .thenComparing(Visit::getServiceDate).thenComparing(Visit::getSequenceInDay));
            Batch batch = batch(visits);
            List<VisitResponses.VisitRow> rows = visits.stream()
                    .map(v -> row(v, batch, ctx.planTitles(), role, now, false)).toList();
            return new VisitResponses.VisitList(orderId, ctx.status().name(), rows);
        });
    }

    // ------------------------------------------------------------------ F2

    public VisitResponses.VisitDetail detail(UUID orderId, UUID visitId) {
        CurrentActor actor = CurrentActor.fromToken();
        VisitRef ref = guard.visit(orderId, visitId);
        ActorRole role = guard.readerRole(orderId, ref.ownerId(), ref.sitterId(), actor);
        OffsetDateTime now = clock.now();
        return tx.readOnly(() -> {
            OrderQueryService.OrderContextView ctx = context(orderId);
            Visit v = visitRepository.findById(visitId).orElseThrow(FulfillException::notFound);
            Batch batch = batch(List.of(v));
            VisitResponses.VisitRow r = row(v, batch, ctx.planTitles(), role, now, false);
            List<String> actions = role == ActorRole.SITTER
                    ? allowedActions(ctx.status(), v, batch.reports.get(v.getId()), now) : List.of();
            LocalDate today = VisitRules.taipeiDate(now);
            boolean zombiePassed = v.getStatus() == VisitStatus.PENDING && !now.isBefore(rules.zombieAt(v.getServiceDate()));
            return new VisitResponses.VisitDetail(r.visitId(), orderId, ctx.status().name(), r.orderItemId(), r.planId(),
                    r.serviceDate(), r.sequenceInDay(), r.slotsInDay(), r.name(), r.customName(), r.status(),
                    r.startedAt(), r.completedAt(), r.completionSource(), r.closedAt(), r.cancelledAt(), r.zombieAt(),
                    r.careLog(), r.checklist(), new VisitResponses.Party(ctx.ownerId(), ctx.ownerDisplayName()),
                    pets(ctx, v), actions, !today.isBefore(v.getServiceDate()), zombiePassed);
        });
    }

    /** 只供畫面啟用 / 停用（PRD-008 §4.1 步驟 2「畫面狀態只是提示」）；判斷以鎖外資料與注入 Clock。 */
    private List<String> allowedActions(OrderStatus orderStatus, Visit v, VisitServiceReport report, OffsetDateTime now) {
        List<String> out = new ArrayList<>();
        boolean active = VisitRules.ACTIVE_ORDER.contains(orderStatus);
        boolean running = VisitRules.RUNNING_ORDER.contains(orderStatus);
        VisitStatus s = v.getStatus();
        if (active && s == VisitStatus.PENDING && now.isBefore(rules.zombieAt(v.getServiceDate()))
                && !VisitRules.taipeiDate(now).isBefore(v.getServiceDate())) {
            out.add("START");
        }
        if (running && s == VisitStatus.IN_PROGRESS) {
            out.add("COMPLETE");
        }
        boolean expired = rules.careLogExpired(v, now);
        if (active && s != VisitStatus.CLOSED_BY_SYSTEM && s != VisitStatus.CANCELLED && !expired) {
            out.add("RENAME");
        }
        if (active && (s == VisitStatus.PENDING || s == VisitStatus.IN_PROGRESS)) {
            out.add("EDIT_CHECKLIST");
        }
        if (running && s == VisitStatus.IN_PROGRESS) {
            out.add("CHECK_ITEM");
        }
        boolean logOpen = report == null || !VisitServiceReport.SUBMITTED.equals(report.getStatus());
        if (running && logOpen && (s == VisitStatus.IN_PROGRESS || (s == VisitStatus.DONE && !expired))) {
            out.add("EDIT_CARE_LOG");
        }
        if (running && logOpen && s == VisitStatus.DONE && !expired) {
            out.add("SUBMIT_CARE_LOG");
        }
        return out;
    }

    // ------------------------------------------------------------------ F6 / F7

    public VisitResponses.SitterDashboard sitterDashboard() {
        UUID sitterId = CurrentActor.fromToken().userId();
        OffsetDateTime now = clock.now();
        LocalDate today = VisitRules.taipeiDate(now);
        return tx.readOnly(() -> {
            List<Visit> todays = visitRepository.findBySitterIdAndServiceDateAndStatusNot(sitterId, today, VisitStatus.CANCELLED);
            List<Visit> overdue = visitRepository.findBySitterIdAndServiceDateBeforeAndStatusIn(sitterId, today,
                    EnumSet.of(VisitStatus.PENDING, VisitStatus.IN_PROGRESS));
            Map<UUID, OrderQueryService.OrderContextView> ctx = contexts(todays, overdue);
            return new VisitResponses.SitterDashboard(cards(todays, ctx, true, now), cards(overdue, ctx, true, now));
        });
    }

    public VisitResponses.OwnerToday ownerToday() {
        UUID ownerId = CurrentActor.fromToken().userId();
        OffsetDateTime now = clock.now();
        LocalDate today = VisitRules.taipeiDate(now);
        return tx.readOnly(() -> {
            List<Visit> todays = visitRepository.findByOwnerIdAndServiceDateAndStatusNot(ownerId, today, VisitStatus.CANCELLED);
            Map<UUID, OrderQueryService.OrderContextView> ctx = contexts(todays, List.of());
            return new VisitResponses.OwnerToday(cards(todays, ctx, false, now));
        });
    }

    private Map<UUID, OrderQueryService.OrderContextView> contexts(List<Visit> a, List<Visit> b) {
        Set<UUID> ids = new LinkedHashSet<>();
        a.forEach(v -> ids.add(v.getOrderId()));
        b.forEach(v -> ids.add(v.getOrderId()));
        Map<UUID, OrderQueryService.OrderContextView> out = new HashMap<>();
        List<UUID> all = new ArrayList<>(ids);
        for (int i = 0; i < all.size(); i += OrderQueryService.MAX_CONTEXT_BATCH) {
            out.putAll(orderQueryService.getOrderContexts(new LinkedHashSet<>(
                    all.subList(i, Math.min(all.size(), i + OrderQueryService.MAX_CONTEXT_BATCH)))));
        }
        return out;
    }

    /** 排序：訂單 createdAt → service_date → 方案群組順序 → sequence_in_day（PRD-008 BR-15）。 */
    private List<VisitResponses.DashboardCard> cards(List<Visit> visits, Map<UUID, OrderQueryService.OrderContextView> ctx,
                                                     boolean forSitter, OffsetDateTime now) {
        List<Visit> kept = visits.stream().filter(v -> {
            OrderQueryService.OrderContextView c = ctx.get(v.getOrderId());
            return c != null && DASHBOARD_ORDER.contains(c.status());
        }).collect(Collectors.toCollection(ArrayList::new));
        Map<UUID, Integer> itemOrder = new HashMap<>();
        ctx.values().forEach(c -> itemOrder.putAll(itemOrder(c)));
        kept.sort(Comparator.comparing((Visit v) -> ctx.get(v.getOrderId()).createdAt())
                .thenComparing(Visit::getOrderId)
                .thenComparing(Visit::getServiceDate)
                .thenComparing(v -> itemOrder.getOrDefault(v.getOrderItemId(), Integer.MAX_VALUE))
                .thenComparing(Visit::getSequenceInDay));
        return kept.stream().map(v -> {
            OrderQueryService.OrderContextView c = ctx.get(v.getOrderId());
            boolean pending = v.getStatus() == VisitStatus.PENDING;
            OffsetDateTime zombieAt = pending ? rules.zombieAt(v.getServiceDate()) : null;
            VisitResponses.Party counterpart = forSitter
                    ? new VisitResponses.Party(c.ownerId(), c.ownerDisplayName())
                    : new VisitResponses.Party(c.sitterId(), c.sitterDisplayName());
            return new VisitResponses.DashboardCard(v.getId(), v.getOrderId(), v.getServiceDate(),
                    nameResolver.name(v, c.planTitles()), v.getStatus().name(), v.getCompletionSource(), zombieAt,
                    pending && !now.isBefore(zombieAt), counterpart, pets(c, v));
        }).toList();
    }

    // ------------------------------------------------------------------ F8

    public VisitResponses.Checklist checklist(UUID orderId, UUID visitId) {
        CurrentActor actor = CurrentActor.fromToken();
        VisitRef ref = guard.visit(orderId, visitId);
        guard.readerRole(orderId, ref.ownerId(), ref.sitterId(), actor);
        return tx.readOnly(() -> new VisitResponses.Checklist(visitId,
                checklistRepository.findByVisitIdOrderBySortOrderAsc(visitId).stream().map(i -> item(i, true)).toList()));
    }

    // ------------------------------------------------------------------ 共用組裝

    record Batch(Map<UUID, VisitServiceReport> reports, Map<UUID, List<VisitChecklistItem>> items) {
    }

    Batch batch(List<Visit> visits) {
        if (visits.isEmpty()) {
            return new Batch(Map.of(), Map.of());
        }
        List<UUID> ids = visits.stream().map(Visit::getId).toList();
        Map<UUID, VisitServiceReport> reports = reportRepository.findByVisitIdIn(ids).stream()
                .collect(Collectors.toMap(VisitServiceReport::getVisitId, Function.identity()));
        Map<UUID, List<VisitChecklistItem>> items = checklistRepository.findByVisitIdInOrderBySortOrderAsc(ids).stream()
                .collect(Collectors.groupingBy(VisitChecklistItem::getVisitId));
        return new Batch(reports, items);
    }

    VisitResponses.VisitRow row(Visit v, Batch batch, Map<UUID, String> titles, ActorRole role, OffsetDateTime now,
                                boolean withSource) {
        VisitServiceReport report = batch.reports.get(v.getId());
        boolean submitted = report != null && VisitServiceReport.SUBMITTED.equals(report.getStatus());
        OffsetDateTime deadline = v.getStatus() == VisitStatus.DONE ? rules.careLogDeadline(v) : null;
        VisitResponses.CareLogSummary careLog = role == ActorRole.SITTER
                ? new VisitResponses.CareLogSummary(submitted, report == null ? null : report.getStatus(), deadline,
                rules.careLogExpired(v, now))
                : new VisitResponses.CareLogSummary(submitted, null, deadline, null);
        List<VisitChecklistItem> items = batch.items.getOrDefault(v.getId(), List.of());
        int done = (int) items.stream().filter(VisitChecklistItem::isCompleted).count();
        VisitResponses.ChecklistSummary checklist = new VisitResponses.ChecklistSummary(items.size(), done,
                items.stream().map(i -> item(i, withSource)).toList());
        return new VisitResponses.VisitRow(v.getId(), v.getOrderItemId(), v.getPlanId(), v.getServiceDate(),
                v.getSequenceInDay(), v.getSlotsInDay(), nameResolver.name(v, titles), v.getCustomName(),
                v.getStatus().name(), v.getStartedAt(), v.getCompletedAt(), v.getCompletionSource(), v.getClosedAt(),
                v.getCancelledAt(), v.getStatus() == VisitStatus.PENDING ? rules.zombieAt(v.getServiceDate()) : null,
                careLog, checklist);
    }

    static VisitResponses.ChecklistItem item(VisitChecklistItem i, boolean withSource) {
        return new VisitResponses.ChecklistItem(i.getId(), i.getTitle(), i.getSortOrder(), i.isCompleted(), i.getCheckedAt(),
                withSource ? i.getCreatedSource() : null);
    }

    OrderQueryService.OrderContextView context(UUID orderId) {
        OrderQueryService.OrderContextView ctx = orderQueryService.getOrderContexts(Set.of(orderId)).get(orderId);
        if (ctx == null) {
            throw FulfillException.notFound();
        }
        return ctx;
    }

    private static Map<UUID, Integer> itemOrder(OrderQueryService.OrderContextView ctx) {
        Map<UUID, Integer> out = new HashMap<>();
        ctx.items().forEach(i -> out.put(i.orderItemId(), i.sortIndex()));
        return out;
    }

    private static List<VisitResponses.PetRef> pets(OrderQueryService.OrderContextView ctx, Visit v) {
        return ctx.petsByItem().getOrDefault(v.getOrderItemId(), List.of()).stream()
                .map(p -> new VisitResponses.PetRef(p.petId(), p.name())).toList();
    }
}
