package com.petsitter.application.service.order;

import com.petsitter.application.service.fulfill.VisitQueryService;
import com.petsitter.application.service.fulfill.VisitView;
import com.petsitter.domain.model.order.ActorRole;
import com.petsitter.domain.model.order.CancelReasonCode;
import com.petsitter.domain.model.order.ModificationRequest;
import com.petsitter.domain.model.order.ModificationStatus;
import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.repository.order.ModificationRequestRepository;
import com.petsitter.domain.repository.order.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 訂單聚合內共用的衍生值與連動（已收淨額、completion_basis_at 重算、O10 檢查）。全部在持鎖交易內使用。 */
@Component
@RequiredArgsConstructor
public class OrderAggregateSupport {

    private final PaymentRepository paymentRepository;
    private final ModificationRequestRepository modificationRequestRepository;
    private final VisitQueryService visitQueryService;
    private final CompletionBasisCalculator completionBasisCalculator;
    private final OrderCancellationApplier cancellationApplier;
    private final OrderLogWriter orderLogWriter;
    private final OrderNotifier notifier;
    private final OrderClock clock;

    public int netPaid(Order order) {
        paymentRepository.flush();
        return paymentRepository.netPaid(order.getId());
    }

    /** 重算並寫入 {@code completion_basis_at}（§3.1.3 重算觸發）。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recomputeCompletionBasis(Order order) {
        List<VisitView> visits = visitQueryService.listVisits(order.getId());
        List<ModificationRequest> settled = modificationRequestRepository
                .findByOrderIdAndStatusOrderByEndedAtAsc(order.getId(), ModificationStatus.SETTLED);
        order.setCompletionBasisAt(completionBasisCalculator.compute(visits, settled));
    }

    /**
     * O10：CONFIRMED 且已無有效行程 → 取消共同附帶動作，另寫 {@code ORDER_CANCELLED_BY_MODIFICATION}（payload
     * {@code modificationId}、{@code initiatedBy}）、enqueue 雙方。回傳是否觸發。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean cancelIfNoEffectiveVisits(LockedOrder locked, ModificationRequest mod) {
        Order order = locked.entity();
        if (order.getStatus() != OrderStatus.CONFIRMED) {
            return false;
        }
        boolean anyEffective = visitQueryService.listVisits(order.getId()).stream().anyMatch(VisitView::effective);
        if (anyEffective) {
            return false;
        }
        List<Map<String, Object>> transitions = new ArrayList<>(cancellationApplier.apply(locked, ActorRole.SYSTEM,
                CancelReasonCode.MODIFICATION, null, null));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("modificationId", mod.getId().toString());
        payload.put("initiatedBy", mod.getInitiatedBy().name());
        payload.put("transitions", transitions);
        orderLogWriter.write(locked, "ORDER_CANCELLED_BY_MODIFICATION", Actor.SYSTEM, payload);
        notifier.both(order, "ORDER_CANCELLED_BY_MODIFICATION", false, Map.of());
        return true;
    }

    public OrderClock clock() {
        return clock;
    }
}
