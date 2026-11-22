package com.petsitter.application.service.order;

import com.petsitter.application.dto.order.ModificationRequests;
import com.petsitter.application.dto.order.ModificationResponses;
import com.petsitter.application.exception.OrderException;
import com.petsitter.application.service.fulfill.VisitView;
import com.petsitter.domain.model.order.ActorRole;
import com.petsitter.domain.model.order.ModificationRequest;
import com.petsitter.domain.model.order.ModificationType;
import com.petsitter.domain.model.order.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 變更金額試算 E32（對應 E17 輸入）/ E33（對應 E20 輸入）（SD-ORDER §6.3 E32 / E33、§11.4）。
 * 整支在一個唯讀交易內；不取訂單鎖、不冪等、不寫 {@code modification_requests} / {@code order_logs} / {@code notifications} /
 * {@code idempotency_records}、不改 {@code orders.version}。檢查序列與計價經 {@link ModificationEvaluator}（與送出端點同一份）。
 * 文字欄位：只有 OWNER 呼叫 E32 時 {@code reason} 不驗證（CQ-51）；SITTER 呼叫 E32 / E33 照送出端點驗證。
 */
@Service
@RequiredArgsConstructor
public class ModificationPreviewService {

    private final OrderCommandSupport support;
    private final OrderRequestValidator validator;
    private final ModificationPlanner planner;
    private final ModificationEvaluator evaluator;
    private final OrderClock clock;

    /** E32：G4 → G4a / G5 → G6 → 409 → 422 → 403 → 計價。 */
    public ModificationResponses.Preview previewInitiate(UUID orderId, ModificationRequests.Initiate req) {
        CurrentActor actor = CurrentActor.fromToken();
        return support.readOnly(() -> {
            Order order = support.loadOrder(orderId);
            boolean sitter = OrderAccess.partyRole(order, actor) == ActorRole.SITTER;
            if (req == null) {
                throw OrderException.validation("type");
            }
            ModificationType type = planner.type(req.type());
            // CQ-51：飼主改單試算不驗 reason；保母試算照 E17 驗證
            String reason = sitter ? validator.requiredText(req.reason(), "reason", 200) : null;
            ModificationPlanner.Draft draft = planner.parse(type, reason, req.removeVisitIds(), req.addItems(),
                    req.surchargeAmount(), req.discountAmount(), req.adjustmentReason(), sitter);
            OffsetDateTime now = clock.now();
            ModificationEvaluator.Evaluation ev = evaluator.evaluateInitiate(order, draft, sitter);
            return toResponse(type, order, ev, sitter, now);
        });
    }

    /** E33：G4（訂單、變更）→ G5 → G6 → 409 → 422 → 403 → 計價。 */
    public ModificationResponses.Preview previewQuote(UUID orderId, UUID modificationId, ModificationRequests.Quote req) {
        CurrentActor actor = CurrentActor.fromToken();
        return support.readOnly(() -> {
            Order order = support.loadOrder(orderId);
            ModificationRequest m = support.loadModification(order, modificationId);
            OrderAccess.requireSitter(order, actor);
            ModificationRequests.Quote r = req == null ? new ModificationRequests.Quote(null, null, null, null, null) : req;
            ModificationPlanner.Draft draft = planner.parse(m.getType(), m.getReason(), r.removeVisitIds(), r.addItems(),
                    r.surchargeAmount(), r.discountAmount(), r.adjustmentReason(), true);
            OffsetDateTime now = clock.now();
            ModificationEvaluator.Evaluation ev = evaluator.evaluateQuote(order, m, draft, now);
            return toResponse(m.getType(), order, ev, true, now);
        });
    }

    private static ModificationResponses.Preview toResponse(ModificationType type, Order order, ModificationEvaluator.Evaluation ev,
                                                            boolean sitter, OffsetDateTime now) {
        Map<UUID, VisitView> visits = ev.context().visits().stream()
                .collect(Collectors.toMap(VisitView::visitId, Function.identity()));
        List<UUID> removeIds = ev.content().removeVisitIds();
        if (type == ModificationType.CANCEL_ALL) {
            removeIds = removeIds.stream()
                    .sorted(Comparator.comparing((UUID id) -> visits.get(id).serviceDate()).thenComparing(Function.identity()))
                    .toList();
        }
        List<ModificationResponses.PreviewKept> kept = ev.pricing().kept().stream()
                .map(k -> new ModificationResponses.PreviewKept(k.visit().visitId(), k.visit().planId(), k.visit().serviceDate(),
                        k.visit().status().name(), k.unitPrice()))
                .toList();
        List<ModificationResponses.PreviewAdded> added = ev.pricing().added().stream()
                .map(a -> new ModificationResponses.PreviewAdded(a.index(), a.item().date(), a.item().planId(),
                        a.item().dailyCount(), a.unitPrice(), a.subtotal()))
                .toList();
        String pricingMode = sitter
                ? (ev.adjustmentAllowed() ? PlanPolicy.PricingMode.CUSTOM_ALLOWED : PlanPolicy.PricingMode.SYSTEM_ONLY).name()
                : null;
        return new ModificationResponses.Preview(type.name(), removeIds,
                new ModificationResponses.PreviewBreakdown(kept, added), ev.systemAmount(), ev.surcharge(), ev.discount(),
                ev.newTotalAmount(), order.getTotalAmount() == null ? 0 : order.getTotalAmount(), ev.netPaid(), ev.diff(),
                pricingMode, now);
    }
}
