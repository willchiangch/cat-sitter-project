package com.petsitter.application.service.order;

import com.petsitter.application.service.fulfill.VisitCommandService;
import com.petsitter.application.service.fulfill.VisitSpec;
import com.petsitter.domain.model.order.InitiatedBy;
import com.petsitter.domain.model.order.ModificationContent;
import com.petsitter.domain.model.order.ModificationRequest;
import com.petsitter.domain.model.order.ModificationStatus;
import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.OrderItem;
import com.petsitter.domain.model.order.OrderItemPet;
import com.petsitter.domain.model.order.OrderItemSource;
import com.petsitter.domain.model.order.OrderPlanSnapshot;
import com.petsitter.domain.repository.order.OrderItemPetRepository;
import com.petsitter.domain.repository.order.OrderItemRepository;
import com.petsitter.domain.repository.order.OrderPlanSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 變更 {@code SETTLED} 的共同動作（SD-ORDER §3.3 同意差額 0 / T7 / T10）：建 MODIFICATION {@code order_items} 與行程
 * （日期已過仍建，CQ-49）、{@code total_amount = new_total_amount}（SYSTEM 退款變更不改總額）、{@code ended_at}、
 * 重算 {@code completion_basis_at}、檢查 O10。須持訂單鎖。
 */
@Component
@RequiredArgsConstructor
public class ModificationApplier {

    private final OrderItemRepository orderItemRepository;
    private final OrderItemPetRepository orderItemPetRepository;
    private final OrderPlanSnapshotRepository planSnapshotRepository;
    private final VisitCommandService visitCommandService;
    private final OrderAggregateSupport aggregateSupport;
    private final OrderClock clock;

    public record SettleResult(List<UUID> createdVisitIds, boolean orderCancelled) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SettleResult settle(LockedOrder locked, ModificationRequest mod) {
        Order order = locked.entity();
        OffsetDateTime now = clock.now();
        List<UUID> created = List.of();
        if (mod.getInitiatedBy() != InitiatedBy.SYSTEM) {
            created = createAddedVisits(locked, mod, now);
            order.setTotalAmount(mod.getNewTotalAmount());
        }
        mod.setStatus(ModificationStatus.SETTLED);
        mod.setEndedAt(now);
        aggregateSupport.recomputeCompletionBasis(order);
        boolean cancelled = aggregateSupport.cancelIfNoEffectiveVisits(locked, mod);
        return new SettleResult(created, cancelled);
    }

    private List<UUID> createAddedVisits(LockedOrder locked, ModificationRequest mod, OffsetDateTime now) {
        ModificationContent content = mod.getQuotedContent();
        if (content == null || content.addItems().isEmpty()) {
            return List.of();
        }
        Map<UUID, OrderPlanSnapshot> snaps = planSnapshotRepository.findByOrderId(locked.orderId()).stream()
                .collect(Collectors.toMap(OrderPlanSnapshot::getPlanId, Function.identity()));
        List<VisitSpec> specs = new ArrayList<>();
        for (ModificationContent.AddItem add : content.addItems()) {
            OrderPlanSnapshot snap = snaps.get(add.planId());
            LocalDate date = LocalDate.parse(add.date());
            OrderItem item = orderItemRepository.save(OrderItem.builder()
                    .orderId(locked.orderId())
                    .source(OrderItemSource.MODIFICATION)
                    .modificationRequestId(mod.getId())
                    .planId(add.planId())
                    .serviceDates(new LocalDate[]{date})
                    .dailyCount((short) add.dailyCount())
                    .bookedUnitPrice(snap.getUnitPrice())
                    .createdAt(now)
                    .build());
            for (UUID petId : add.petIds()) {
                orderItemPetRepository.save(OrderItemPet.of(item.getId(), petId));
            }
            for (int seq = 1; seq <= add.dailyCount(); seq++) {
                specs.add(new VisitSpec(item.getId(), add.planId(), snap.getPlanTitle(), date, seq));
            }
        }
        return visitCommandService.createVisits(locked, specs);
    }

    /** {@code MODIFICATION_ACCEPTED} 等 log 用：變更內容摘要。 */
    static Map<String, Object> describe(ModificationContent c) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (c == null) {
            return m;
        }
        m.put("removeVisitIds", c.removeVisitIds().stream().map(UUID::toString).toList());
        m.put("addItems", c.addItems().stream().map(a -> Map.of("date", a.date(), "planId", a.planId().toString(),
                "dailyCount", a.dailyCount(), "petIds", a.petIds().stream().map(UUID::toString).toList())).toList());
        return m;
    }
}
