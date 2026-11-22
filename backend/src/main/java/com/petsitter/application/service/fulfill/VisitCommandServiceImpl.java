package com.petsitter.application.service.fulfill;

import com.petsitter.application.exception.VisitNotPendingException;
import com.petsitter.application.service.NotificationService;
import com.petsitter.application.service.notify.NotificationCommand;
import com.petsitter.application.service.order.LockedOrder;
import com.petsitter.application.service.order.OrderLockService;
import com.petsitter.domain.model.fulfill.Visit;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.model.order.VisitStatus;
import com.petsitter.domain.repository.fulfill.VisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SD-FULFILL §2.3.1 介面的 B 階段最小實作（只含 SD-ORDER 需要的行為）。
 * TODO(C 階段 F-M2)：依 SD-FULFILL 全面重寫（檢核項帶入 §4.8、命名、FulfillAggregateArchTest）。
 */
@Service
@RequiredArgsConstructor
public class VisitCommandServiceImpl implements VisitCommandService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final Set<OrderStatus> ZOMBIE_ORDER_STATUSES =
            EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.IN_PROGRESS, OrderStatus.DISPUTED);

    private final VisitRepository visitRepository;
    private final OrderLockService orderLockService;
    private final NotificationService notificationService;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> createVisits(LockedOrder order, List<VisitSpec> specs) {
        orderLockService.requireHeld(order);
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
        return visitRepository.saveAll(visits).stream().map(Visit::getId).toList();
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
        Set<UUID> notPending = new java.util.HashSet<>(visitIds);
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
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> closeZombieVisits(LockedOrder order, OffsetDateTime now, Duration zombieAfter) {
        orderLockService.requireHeld(order);
        if (!ZOMBIE_ORDER_STATUSES.contains(order.status())) {
            return List.of();
        }
        List<Visit> closed = new ArrayList<>();
        for (Visit v : visitRepository.findByOrderIdAndStatus(order.orderId(), VisitStatus.PENDING)) {
            OffsetDateTime zombieAt = v.getServiceDate().atStartOfDay(TAIPEI).toOffsetDateTime().plus(zombieAfter);
            if (!zombieAt.isAfter(now)) {
                v.setStatus(VisitStatus.CLOSED_BY_SYSTEM);
                v.setClosedAt(now);
                closed.add(v);
            }
        }
        if (closed.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> listed = closed.stream().map(v -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("serviceDate", v.getServiceDate().toString());
            m.put("name", v.getCustomName() != null ? v.getCustomName() : v.getBookingPlanTitle());
            return m;
        }).toList();
        Map<String, Object> params = Map.of("orderId", order.orderId().toString(), "visits", listed);
        notificationService.enqueue(new NotificationCommand(order.sitterId(), "SITTER", "VISIT_CLOSED_BY_SYSTEM_SITTER",
                false, order.orderId(), "/sitter/orders/" + order.orderId(), params));
        notificationService.enqueue(new NotificationCommand(order.ownerId(), "OWNER", "VISIT_CLOSED_BY_SYSTEM_OWNER",
                false, order.orderId(), "/owner/orders/" + order.orderId(), params));
        return closed.stream().map(Visit::getId).toList();
    }
}
