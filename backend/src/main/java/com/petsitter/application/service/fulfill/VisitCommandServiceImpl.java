package com.petsitter.application.service.fulfill;

import com.petsitter.application.common.text.TextRules;
import com.petsitter.application.exception.VisitNotPendingException;
import com.petsitter.application.port.ServicePlanQuery;
import com.petsitter.application.service.order.LockedOrder;
import com.petsitter.application.service.order.OrderLockService;
import com.petsitter.application.service.order.OrderQueryService;
import com.petsitter.domain.model.fulfill.Visit;
import com.petsitter.domain.model.fulfill.VisitChecklistItem;
import com.petsitter.domain.model.order.VisitStatus;
import com.petsitter.domain.repository.fulfill.VisitChecklistItemRepository;
import com.petsitter.domain.repository.fulfill.VisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SD-ORDER §2.4.2 / SD-FULFILL §2.3.1 的行程寫入介面實作。全部 {@code MANDATORY} 且須已持有訂單鎖；
 * 不寫 {@code order_logs}（由呼叫端寫）。{@link #closeZombieVisits} 本批非空時同交易通知雙方（§4.7）。
 */
@Service
@RequiredArgsConstructor
public class VisitCommandServiceImpl implements VisitCommandService {

    private final VisitRepository visitRepository;
    private final VisitChecklistItemRepository checklistRepository;
    private final ServicePlanQuery servicePlanQuery;
    private final OrderLockService orderLockService;
    private final OrderQueryService orderQueryService;
    private final VisitNameResolver nameResolver;
    private final FulfillNotifier notifier;
    private final FulfillSettings settings;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> createVisits(LockedOrder order, List<VisitSpec> specs) {
        orderLockService.requireHeld(order);
        if (specs.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> slots = new HashMap<>();
        for (VisitSpec s : specs) {
            slots.merge(s.orderItemId() + "|" + s.serviceDate(), 1, Integer::sum);
        }
        List<Visit> visits = new ArrayList<>();
        for (VisitSpec s : specs) {
            visits.add(Visit.builder()
                    .orderId(order.orderId())
                    .orderItemId(s.orderItemId())
                    .ownerId(order.ownerId())
                    .sitterId(order.sitterId())
                    .status(VisitStatus.PENDING)
                    .planId(s.planId())
                    .bookingPlanTitle(s.planTitle())
                    .serviceDate(s.serviceDate())
                    .sequenceInDay((short) s.sequenceInDay())
                    .slotsInDay(slots.get(s.orderItemId() + "|" + s.serviceDate()).shortValue())
                    .build());
        }
        List<Visit> saved = visitRepository.saveAll(visits);
        // 檢核項以 visit_id（純 UUID 欄）參照行程：先送出行程 INSERT，再整批寫入檢核項（JDBC batch，§4.8 第 4 點）
        visitRepository.flush();
        seedChecklist(saved);
        return saved.stream().map(Visit::getId).toList();
    }

    /** §4.8：一次查詢方案預設任務 → 依序複製（略過空白、strip 後截至 50 code point、只取前 20 項）。 */
    private void seedChecklist(List<Visit> visits) {
        Set<UUID> planIds = new LinkedHashSet<>();
        visits.forEach(v -> planIds.add(v.getPlanId()));
        Map<UUID, List<String>> tasks = servicePlanQuery.findDefaultTasks(planIds);
        Map<UUID, List<String>> normalized = new HashMap<>();
        tasks.forEach((planId, list) -> normalized.put(planId, normalize(list)));
        List<VisitChecklistItem> items = new ArrayList<>();
        for (Visit v : visits) {
            List<String> titles = normalized.getOrDefault(v.getPlanId(), List.of());
            for (int i = 0; i < titles.size(); i++) {
                items.add(VisitChecklistItem.builder()
                        .visitId(v.getId())
                        .title(titles.get(i))
                        .sortOrder((short) i)
                        .completed(false)
                        .createdSource(VisitChecklistItem.SOURCE_SYSTEM)
                        .build());
            }
        }
        if (!items.isEmpty()) {
            checklistRepository.saveAll(items);
        }
    }

    private List<String> normalize(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        int max = settings.getChecklistTitleMax();
        for (String t : raw) {
            if (out.size() >= settings.getChecklistMaxItems()) {
                break;
            }
            String s = TextRules.strip(t);
            if (s == null || s.isEmpty()) {
                continue;
            }
            if (TextRules.codePointLength(s) > max) {
                s = s.substring(0, s.offsetByCodePoints(0, max));
            }
            out.add(s);
        }
        return out;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> cancelPendingVisits(LockedOrder order, OffsetDateTime now) {
        orderLockService.requireHeld(order);
        List<UUID> ids = new ArrayList<>();
        for (Visit v : visitRepository.findByOrderIdAndStatus(order.orderId(), VisitStatus.PENDING)) {
            v.setStatus(VisitStatus.CANCELLED);
            v.setCancelledAt(now);
            ids.add(v.getId());
        }
        visitRepository.flush();
        return ids;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelVisits(LockedOrder order, Set<UUID> visitIds, OffsetDateTime now) {
        orderLockService.requireHeld(order);
        if (visitIds.isEmpty()) {
            return;
        }
        List<Visit> visits = visitRepository.findAllById(visitIds);
        Set<UUID> notPending = new HashSet<>(visitIds);
        for (Visit v : visits) {
            if (v.getOrderId().equals(order.orderId()) && v.getStatus() == VisitStatus.PENDING) {
                notPending.remove(v.getId());
            }
        }
        if (!notPending.isEmpty()) {
            throw new VisitNotPendingException(notPending);
        }
        for (Visit v : visits) {
            v.setStatus(VisitStatus.CANCELLED);
            v.setCancelledAt(now);
        }
        visitRepository.flush();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> closeZombieVisits(LockedOrder order, OffsetDateTime now, Duration zombieAfter) {
        orderLockService.requireHeld(order);
        if (!VisitRules.ACTIVE_ORDER.contains(order.status())) {
            return List.of();
        }
        List<Visit> closed = new ArrayList<>();
        for (Visit v : visitRepository.findByOrderIdOrderByServiceDateAscSequenceInDayAsc(order.orderId())) {
            if (v.getStatus() != VisitStatus.PENDING) {
                continue;
            }
            OffsetDateTime zombieAt = com.petsitter.application.service.order.OrderClock.startOfDay(v.getServiceDate()).plus(zombieAfter);
            if (!now.isBefore(zombieAt)) {
                v.setStatus(VisitStatus.CLOSED_BY_SYSTEM);
                v.setClosedAt(now);
                closed.add(v);
            }
        }
        if (closed.isEmpty()) {
            return List.of();
        }
        visitRepository.flush();
        Map<UUID, String> titles = planTitles(order.orderId());
        notifier.zombieClosed(order, closed.stream().map(v -> new FulfillNotifier.NamedVisit(v.getId(),
                v.getServiceDate().toString(), nameResolver.name(v, titles))).toList());
        return closed.stream().map(Visit::getId).toList();
    }

    private Map<UUID, String> planTitles(UUID orderId) {
        OrderQueryService.OrderContextView ctx = orderQueryService.getOrderContexts(Set.of(orderId)).get(orderId);
        return ctx == null ? Map.of() : ctx.planTitles();
    }
}
