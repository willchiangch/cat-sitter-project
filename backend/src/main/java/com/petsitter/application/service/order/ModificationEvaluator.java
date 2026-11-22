package com.petsitter.application.service.order;

import com.petsitter.application.exception.OrderErrorCode;
import com.petsitter.application.exception.OrderException;
import com.petsitter.application.port.SubscriptionQuery.SubscriptionStatus;
import com.petsitter.domain.model.order.ModificationContent;
import com.petsitter.domain.model.order.ModificationRequest;
import com.petsitter.domain.model.order.ModificationStatus;
import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.repository.order.ModificationRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 變更發起 / 報價的檢查序列與計價單一來源（SD-ORDER §3.3「計價與檢查單一來源」、§6.3 E17 / E20 / E32 / E33）：
 * G6 伺服器資料檢查 → 409 → 422 → 403 {@code PLAN_REQUIRED} → 計價（{@link ModificationPlanner#price}）→ 已收淨額。
 * E17 / E20 在訂單鎖內、E32 / E33 在唯讀交易內呼叫同一個方法；本類別不寫入任何資料。
 */
@Component
@RequiredArgsConstructor
public class ModificationEvaluator {

    private final ModificationPlanner planner;
    private final ModificationRequestRepository modificationRequestRepository;
    private final OrderAggregateSupport aggregateSupport;
    private final PlanPolicy planPolicy;

    /** 檢查與計價結果（{@code newTotalAmount = systemAmount + surcharge − discount}；OWNER 加減恆為 0）。 */
    public record Evaluation(ModificationContent content, ModificationPlanner.Context context, ModificationPlanner.Pricing pricing,
                             int surcharge, int discount, int newTotalAmount, int netPaid, boolean adjustmentAllowed) {

        public int systemAmount() {
            return pricing.systemAmount();
        }

        public int diff() {
            return newTotalAmount - netPaid;
        }
    }

    /** E17 / E32：{@code draft} 已通過格式檢查（OWNER 的加減欄位已忽略）。 */
    public Evaluation evaluateInitiate(Order order, ModificationPlanner.Draft draft, boolean sitter) {
        ModificationPlanner.Context c = planner.context(order);
        ModificationContent content = planner.content(draft, c);
        planner.validateServerData(draft, content, c, sitter);
        requireModifiable(order);
        if (modificationRequestRepository.existsByOrderIdAndStatusIn(order.getId(), ModificationRequest.OPEN_STATUSES)) {
            throw new OrderException(OrderErrorCode.MODIFICATION_IN_PROGRESS);
        }
        if (c.pendingIds().isEmpty()) {
            throw new OrderException(OrderErrorCode.MODIFICATION_NOT_ALLOWED);
        }
        planner.requireRemovablePending(content, c);
        SubscriptionStatus sub = planPolicy.status(order.getSitterId());
        planner.validateBusiness(draft, content, c, sub, sitter);
        boolean allowed = planPolicy.adjustmentAllowed(sub);
        if (sitter && draft.surcharge() + draft.discount() > 0 && !allowed) {
            throw new OrderException(OrderErrorCode.PLAN_REQUIRED);
        }
        return priced(order, draft, content, c, allowed);
    }

    /** E20 / E33：{@code modification} 已確認屬於 {@code order}（G4）。 */
    public Evaluation evaluateQuote(Order order, ModificationRequest modification, ModificationPlanner.Draft draft, OffsetDateTime now) {
        ModificationPlanner.Context c = planner.context(order);
        ModificationContent content = planner.content(draft, c);
        planner.validateServerData(draft, content, c, true);
        requireModifiable(order);
        if (modification.getStatus() != ModificationStatus.REQUESTED) {
            throw OrderException.modificationConflict(modification.getStatus());
        }
        requireNotExpired(modification, now);
        planner.requireRemovablePending(content, c);
        SubscriptionStatus sub = planPolicy.status(order.getSitterId());
        planner.validateBusiness(draft, content, c, sub, true);
        boolean allowed = planPolicy.adjustmentAllowed(sub);
        if (draft.surcharge() + draft.discount() > 0 && !allowed) {
            throw new OrderException(OrderErrorCode.PLAN_REQUIRED);
        }
        return priced(order, draft, content, c, allowed);
    }

    private Evaluation priced(Order order, ModificationPlanner.Draft draft, ModificationContent content,
                              ModificationPlanner.Context c, boolean allowed) {
        ModificationPlanner.Pricing pricing = planner.price(content, c);
        int newTotal = pricing.systemAmount() + draft.surcharge() - draft.discount();
        int net = aggregateSupport.netPaid(order);
        return new Evaluation(content, c, pricing, draft.surcharge(), draft.discount(), newTotal, net, allowed);
    }

    /** 409 {@code ORDER_STATE_CONFLICT}：變更只在 CONFIRMED / IN_PROGRESS。 */
    static void requireModifiable(Order o) {
        if (o.getStatus() != OrderStatus.CONFIRMED && o.getStatus() != OrderStatus.IN_PROGRESS) {
            throw OrderException.stateConflict(o.getStatus());
        }
    }

    /** 409 {@code DEADLINE_PASSED}（{@code MODIFICATION_EXPIRES_AT}），以時間判斷、不依排程。 */
    static void requireNotExpired(ModificationRequest m, OffsetDateTime now) {
        if (m.getExpiresAt() != null && !now.isBefore(m.getExpiresAt())) {
            throw OrderException.deadline("MODIFICATION_EXPIRES_AT", m.getExpiresAt());
        }
    }
}
