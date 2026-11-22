package com.petsitter.application.service.order;

import com.petsitter.application.dto.order.ModificationRequests;
import com.petsitter.application.exception.OrderErrorCode;
import com.petsitter.application.exception.OrderException;
import com.petsitter.application.port.ServicePlanQuery;
import com.petsitter.application.port.ServicePlanQuery.PlanView;
import com.petsitter.application.port.SubscriptionQuery.SubscriptionStatus;
import com.petsitter.application.service.fulfill.VisitQueryService;
import com.petsitter.application.service.fulfill.VisitView;
import com.petsitter.domain.model.order.ModificationContent;
import com.petsitter.domain.model.order.ModificationType;
import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.OrderItem;
import com.petsitter.domain.model.order.OrderItemPet;
import com.petsitter.domain.model.order.OrderPlanSnapshot;
import com.petsitter.domain.model.order.VisitStatus;
import com.petsitter.domain.repository.order.OrderItemPetRepository;
import com.petsitter.domain.repository.order.OrderItemRepository;
import com.petsitter.domain.repository.order.OrderPlanSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 變更內容的解析、伺服器資料相依的 400（G6，鎖外讀值、鎖後重驗）、422 業務規則與計價（SD-ORDER §3.3、§6.1、§6.3 E17 / E20；
 * PRD-016 BR-28）。計價：{@code system_amount = Σ 快照單價 × 變更後有效行程}；含 DONE / CLOSED_BY_SYSTEM。
 */
@Component
@RequiredArgsConstructor
public class ModificationPlanner {

    static final int MAX_DAILY = 3;
    static final int MAX_DATES = 60;

    private final OrderRequestValidator validator;
    private final VisitQueryService visitQueryService;
    private final OrderItemRepository orderItemRepository;
    private final OrderItemPetRepository orderItemPetRepository;
    private final OrderPlanSnapshotRepository planSnapshotRepository;
    private final ServicePlanQuery servicePlanQuery;
    private final OrderSettings settings;
    private final OrderClock clock;

    /** 格式解析後的草稿（OWNER 的加減欄位忽略）。 */
    public record Draft(ModificationType type, String reason, List<UUID> removeVisitIds,
                        List<ModificationContent.AddItem> addItems, int surcharge, int discount, String adjustmentReason) {
    }

    // ------------------------------------------------------------------ G6 格式

    public ModificationType type(String raw) {
        if ("CHANGE".equals(raw)) {
            return ModificationType.CHANGE;
        }
        if ("CANCEL_ALL".equals(raw)) {
            return ModificationType.CANCEL_ALL;
        }
        throw OrderException.validation("type");
    }

    public Draft parse(ModificationType type, String reason, List<String> removeRaw, List<ModificationRequests.AddItem> addRaw,
                       java.math.BigDecimal surchargeRaw, java.math.BigDecimal discountRaw, String adjustmentReasonRaw,
                       boolean sitter) {
        List<UUID> remove = new ArrayList<>();
        List<ModificationContent.AddItem> adds = new ArrayList<>();
        if (type == ModificationType.CHANGE) {
            Set<UUID> seen = new LinkedHashSet<>();
            for (String s : removeRaw == null ? List.<String>of() : removeRaw) {
                seen.add(validator.uuid(s, "removeVisitIds"));
            }
            remove.addAll(seen);
            List<ModificationRequests.AddItem> raw = addRaw == null ? List.of() : addRaw;
            for (int i = 0; i < raw.size(); i++) {
                ModificationRequests.AddItem a = raw.get(i);
                Map<String, Object> at = Map.of("index", i);
                if (a == null) {
                    throw OrderException.validation("addItems", at);
                }
                LocalDate date;
                UUID planId;
                int daily;
                try {
                    date = validator.date(a.date(), "date");
                    planId = validator.uuid(a.planId(), "planId");
                    daily = validator.amount(a.dailyCount(), "dailyCount", 1, MAX_DAILY, true, 0);
                } catch (OrderException e) {
                    throw OrderException.validation("addItems", at);
                }
                if (a.petIds() == null || a.petIds().isEmpty()) {
                    throw OrderException.validation("petIds", at);
                }
                List<UUID> pets = new ArrayList<>(new LinkedHashSet<>(a.petIds().stream()
                        .map(s -> validator.uuid(s, "petIds")).toList()));
                adds.add(new ModificationContent.AddItem(date.toString(), planId, daily, pets));
            }
        }
        int surcharge = 0;
        int discount = 0;
        String adjReason = null;
        if (sitter) {
            surcharge = validator.amount(surchargeRaw, "surchargeAmount", 0, settings.getMaxAmount(), false, 0);
            discount = validator.amount(discountRaw, "discountAmount", 0, settings.getMaxAmount(), false, 0);
            adjReason = surcharge + discount > 0
                    ? validator.requiredText(adjustmentReasonRaw, "adjustmentReason", 200) : null;
        }
        return new Draft(type, reason, remove, adds, surcharge, discount, adjReason);
    }

    // ------------------------------------------------------------------ 資料快照

    /** 計價 / 驗證需要的訂單現況（同一次讀取）。 */
    public record Context(Order order, List<VisitView> visits, Set<UUID> orderPetIds, Map<UUID, OrderPlanSnapshot> snapshots,
                          Map<UUID, PlanView> plans) {

        public List<VisitView> effective() {
            return visits.stream().filter(VisitView::effective).toList();
        }

        public List<UUID> pendingIds() {
            return visits.stream().filter(v -> v.status() == VisitStatus.PENDING).map(VisitView::visitId).toList();
        }
    }

    public Context context(Order order) {
        List<VisitView> visits = visitQueryService.listVisits(order.getId());
        List<OrderItem> items = orderItemRepository.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId());
        Set<UUID> pets = items.isEmpty() ? Set.of() : orderItemPetRepository.findByOrderItemIds(items.stream().map(OrderItem::getId).toList())
                .stream().map(OrderItemPet::getPetId).collect(Collectors.toSet());
        Map<UUID, OrderPlanSnapshot> snaps = planSnapshotRepository.findByOrderId(order.getId()).stream()
                .collect(Collectors.toMap(OrderPlanSnapshot::getPlanId, Function.identity()));
        Map<UUID, PlanView> plans = servicePlanQuery.findForBooking(order.getSitterId(), snaps.keySet()).stream()
                .collect(Collectors.toMap(PlanView::planId, Function.identity()));
        return new Context(order, visits, pets, snaps, plans);
    }

    /** CANCEL_ALL：減少目標 = 當下所有 PENDING 行程，無新增（PRD-016、baseline §3.4）。 */
    public ModificationContent content(Draft d, Context ctx) {
        if (d.type() == ModificationType.CANCEL_ALL) {
            return new ModificationContent(ctx.pendingIds(), List.of());
        }
        return new ModificationContent(d.removeVisitIds(), d.addItems());
    }

    /**
     * 伺服器資料相依的 400（G6；鎖後重驗一次）：減少目標必須屬本訂單；新增毛孩須在本訂單毛孩範圍；同方案同日合計 ≤ 3；
     * 變更後日期總數 ≤ 60；（SITTER）新總額 ≤ 999,999。
     */
    public void validateServerData(Draft d, ModificationContent c, Context ctx, boolean sitter) {
        Set<UUID> visitIds = ctx.visits().stream().map(VisitView::visitId).collect(Collectors.toSet());
        for (UUID id : c.removeVisitIds()) {
            if (!visitIds.contains(id)) {
                throw OrderException.validation("removeVisitIds", Map.of("visitId", id.toString()));
            }
        }
        for (int i = 0; i < c.addItems().size(); i++) {
            if (!ctx.orderPetIds().containsAll(c.addItems().get(i).petIds())) {
                throw OrderException.validation("petIds", Map.of("index", i));
            }
        }
        Set<UUID> removed = new HashSet<>(c.removeVisitIds());
        Map<String, Integer> perPlanDay = new HashMap<>();
        Set<LocalDate> dates = new TreeSet<>();
        for (VisitView v : ctx.effective()) {
            if (removed.contains(v.visitId())) {
                continue;
            }
            perPlanDay.merge(v.planId() + "|" + v.serviceDate(), 1, Integer::sum);
            dates.add(v.serviceDate());
        }
        for (ModificationContent.AddItem a : c.addItems()) {
            int total = perPlanDay.merge(a.planId() + "|" + a.date(), a.dailyCount(), Integer::sum);
            if (total > MAX_DAILY) {
                throw OrderException.validation("dailyCount", Map.of("date", a.date(), "max", MAX_DAILY));
            }
            dates.add(LocalDate.parse(a.date()));
        }
        if (dates.size() > MAX_DATES) {
            throw OrderException.validation("addItems", Map.of("max", MAX_DATES));
        }
        if (sitter) {
            long newTotal = (long) systemAmount(c, ctx) + d.surcharge() - d.discount();
            if (newTotal > settings.getMaxAmount()) {
                throw OrderException.validation("newTotalAmount", Map.of("max", settings.getMaxAmount()));
            }
        }
    }

    /** 減少目標皆須仍為 PENDING（鎖內以 SD-FULFILL 行程狀態驗證）→ 409 VISIT_ALREADY_STARTED。 */
    public void requireRemovablePending(ModificationContent c, Context ctx) {
        Map<UUID, VisitView> byId = ctx.visits().stream().collect(Collectors.toMap(VisitView::visitId, Function.identity()));
        List<String> started = c.removeVisitIds().stream()
                .filter(id -> byId.get(id) == null || byId.get(id).status() != VisitStatus.PENDING)
                .map(UUID::toString).sorted().toList();
        if (!started.isEmpty()) {
            throw new OrderException(OrderErrorCode.VISIT_ALREADY_STARTED, Map.of("visitIds", started));
        }
    }

    /**
     * 422 業務規則（依序）：INVALID_VISIT_DATE（新增日期須 ≥ 今天 + 1；方案區間依報價當下等級）→ MODIFICATION_EMPTY →
     * PLAN_NOT_IN_ORDER →（SITTER）INVALID_MODIFICATION_AMOUNT。
     */
    public void validateBusiness(Draft d, ModificationContent c, Context ctx, SubscriptionStatus sub, boolean sitter) {
        LocalDate min = clock.todayTaipei().plusDays(1);
        TreeSet<String> tooEarly = new TreeSet<>();
        for (ModificationContent.AddItem a : c.addItems()) {
            if (LocalDate.parse(a.date()).isBefore(min)) {
                tooEarly.add(a.date());
            }
        }
        if (!tooEarly.isEmpty()) {
            throw new OrderException(OrderErrorCode.INVALID_VISIT_DATE, Map.of("dates", List.copyOf(tooEarly), "reason", "DATE_OUT_OF_RANGE"));
        }
        if (PlanPolicy.bookableRangeApplies(sub)) {
            TreeSet<String> outside = new TreeSet<>();
            for (ModificationContent.AddItem a : c.addItems()) {
                PlanView p = ctx.plans().get(a.planId());
                if (p != null && ctx.snapshots().containsKey(a.planId()) && !p.inRange(LocalDate.parse(a.date()))) {
                    outside.add(a.date());
                }
            }
            if (!outside.isEmpty()) {
                throw new OrderException(OrderErrorCode.INVALID_VISIT_DATE, Map.of("dates", List.copyOf(outside), "reason", "PLAN_NOT_IN_RANGE"));
            }
        }
        if (c.isEmpty()) {
            throw new OrderException(OrderErrorCode.MODIFICATION_EMPTY);
        }
        for (ModificationContent.AddItem a : c.addItems()) {
            if (!ctx.snapshots().containsKey(a.planId())) {
                throw new OrderException(OrderErrorCode.PLAN_NOT_IN_ORDER, Map.of("planId", a.planId().toString()));
            }
        }
        if (sitter && (long) systemAmount(c, ctx) + d.surcharge() - d.discount() < 0) {
            throw new OrderException(OrderErrorCode.INVALID_MODIFICATION_AMOUNT);
        }
    }

    /** 保留的有效行程逐列單價（依 serviceDate、visitId 升冪）。 */
    public record KeptLine(VisitView visit, int unitPrice) {
    }

    /** 新增項目逐列（{@code index} 對應 request addItems 索引）：小計 = 單價 × 每日次數。 */
    public record AddedLine(int index, ModificationContent.AddItem item, int unitPrice, int subtotal) {
    }

    /** 計價明細與合計（同源）：{@code systemAmount = Σ kept.unitPrice + Σ added.subtotal}。 */
    public record Pricing(List<KeptLine> kept, List<AddedLine> added, int systemAmount) {
    }

    /**
     * 計價單一來源（SD-ORDER §3.3「計價與檢查單一來源」）：{@code system_amount = Σ(變更後有效行程的快照單價)}；
     * 有效行程 = 非 CANCELLED 且不在減少目標內（含 DONE / CLOSED_BY_SYSTEM，PRD-016 BR-12 / AC-2.4）+ 新增項目（單價 × 每日次數）。
     * E17 / E20 / E32 / E33 皆經此方法。
     */
    public Pricing price(ModificationContent c, Context ctx) {
        Set<UUID> removed = new HashSet<>(c.removeVisitIds());
        List<KeptLine> kept = new ArrayList<>();
        long sum = 0;
        for (VisitView v : ctx.effective()) {
            if (!removed.contains(v.visitId())) {
                int price = unitPrice(ctx, v.planId());
                kept.add(new KeptLine(v, price));
                sum += price;
            }
        }
        kept.sort(java.util.Comparator.comparing((KeptLine k) -> k.visit().serviceDate())
                .thenComparing(k -> k.visit().visitId()));
        List<AddedLine> added = new ArrayList<>();
        for (int i = 0; i < c.addItems().size(); i++) {
            ModificationContent.AddItem a = c.addItems().get(i);
            int price = unitPrice(ctx, a.planId());
            long subtotal = (long) price * a.dailyCount();
            added.add(new AddedLine(i, a, price, (int) Math.min(subtotal, Integer.MAX_VALUE)));
            sum += subtotal;
        }
        return new Pricing(List.copyOf(kept), List.copyOf(added), (int) Math.min(sum, Integer.MAX_VALUE));
    }

    /** {@link #price} 的合計。 */
    public int systemAmount(ModificationContent c, Context ctx) {
        return price(c, ctx).systemAmount();
    }

    /** 本變更減少目標的快照單價合計（C6 補款逾時重算，CQ-53）。 */
    public int removedAmount(ModificationContent c, Context ctx) {
        Map<UUID, VisitView> byId = ctx.visits().stream().collect(Collectors.toMap(VisitView::visitId, Function.identity()));
        long sum = 0;
        for (UUID id : c.removeVisitIds()) {
            VisitView v = byId.get(id);
            if (v != null) {
                sum += unitPrice(ctx, v.planId());
            }
        }
        return (int) sum;
    }

    private static int unitPrice(Context ctx, UUID planId) {
        OrderPlanSnapshot s = ctx.snapshots().get(planId);
        return s == null ? 0 : s.getUnitPrice();
    }
}
