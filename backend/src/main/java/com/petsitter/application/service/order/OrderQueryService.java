package com.petsitter.application.service.order;

import com.petsitter.application.exception.OrderException;
import com.petsitter.application.port.PetQuery;
import com.petsitter.application.port.UserDisplayQuery;
import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.OrderItem;
import com.petsitter.domain.model.order.OrderItemPet;
import com.petsitter.domain.model.order.OrderItemSource;
import com.petsitter.domain.model.order.OrderLog;
import com.petsitter.domain.model.order.OrderPlanSnapshot;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.model.order.ActorRole;
import com.petsitter.domain.repository.order.OrderItemPetRepository;
import com.petsitter.domain.repository.order.OrderItemRepository;
import com.petsitter.domain.repository.order.OrderLogRepository;
import com.petsitter.domain.repository.order.OrderPlanSnapshotRepository;
import com.petsitter.domain.repository.order.OrderRepository;
import com.petsitter.domain.repository.order.OrderSnapshotRepository;
import com.petsitter.domain.repository.order.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 給其他 SD 的唯讀查詢（SD-ORDER §2.3.5、§2.4.1）。全部 {@code SUPPORTS}。 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
public class OrderQueryService {

    public static final int MAX_CONTEXT_BATCH = 200;
    private static final Set<OrderStatus> TERMINAL = EnumSet.of(OrderStatus.COMPLETED, OrderStatus.CANCELLED);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderItemPetRepository orderItemPetRepository;
    private final OrderPlanSnapshotRepository planSnapshotRepository;
    private final OrderSnapshotRepository orderSnapshotRepository;
    private final OrderLogRepository orderLogRepository;
    private final PaymentRepository paymentRepository;
    private final AdminOrderAccessPolicy adminOrderAccessPolicy;
    private final UserDisplayQuery userDisplayQuery;
    private final PetQuery petQuery;

    public record OrderParticipants(UUID ownerId, UUID sitterId, OrderStatus status) {
    }

    public record MediaQuotaSnapshot(String planTier, int maxPhotos, int maxVideos, int mediaRetentionDays) {
    }

    public record PetRef(UUID petId, String name, boolean deleted) {
    }

    public record ItemRef(UUID orderItemId, UUID planId, OrderItemSource source, int sortIndex) {
    }

    public record OrderContextView(UUID orderId, OrderStatus status, OffsetDateTime createdAt, UUID ownerId, UUID sitterId,
                                   String ownerDisplayName, String sitterDisplayName, boolean adminReadable,
                                   List<ItemRef> items, Map<UUID, List<PetRef>> petsByItem, Map<UUID, String> planTitles) {
    }

    /** 批次唯讀訂單脈絡（固定查詢次數，不 N+1）；單次最多 200 筆。 */
    public Map<UUID, OrderContextView> getOrderContexts(Set<UUID> orderIds) {
        if (orderIds.size() > MAX_CONTEXT_BATCH) {
            throw new IllegalArgumentException("at most " + MAX_CONTEXT_BATCH + " order ids");
        }
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        List<Order> orders = orderRepository.findAllById(orderIds);
        if (orders.isEmpty()) {
            return Map.of();
        }
        Set<UUID> found = orders.stream().map(Order::getId).collect(Collectors.toSet());
        List<OrderItem> items = orderItemRepository.findByOrderIdIn(found);
        List<OrderItemPet> bindings = items.isEmpty() ? List.of()
                : orderItemPetRepository.findByOrderItemIds(items.stream().map(OrderItem::getId).toList());
        Map<UUID, PetQuery.PetView> pets = petQuery.findForBooking(bindings.stream().map(OrderItemPet::getPetId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(PetQuery.PetView::petId, Function.identity()));
        List<OrderPlanSnapshot> snapshots = planSnapshotRepository.findByOrderIds(found);
        Set<UUID> readable = adminOrderAccessPolicy.readableIds(orders);
        Map<UUID, String> ownerNames = userDisplayQuery.ownerDisplayNames(orders.stream().map(Order::getOwnerId).collect(Collectors.toSet()));
        Map<UUID, String> sitterNames = userDisplayQuery.sitterDisplayNames(orders.stream().map(Order::getSitterId).collect(Collectors.toSet()));

        Map<UUID, List<OrderItem>> itemsByOrder = items.stream().collect(Collectors.groupingBy(OrderItem::getOrderId));
        Map<UUID, List<OrderItemPet>> bindingsByItem = bindings.stream().collect(Collectors.groupingBy(OrderItemPet::getOrderItemId));
        Map<UUID, Map<UUID, String>> titlesByOrder = new HashMap<>();
        for (OrderPlanSnapshot s : snapshots) {
            titlesByOrder.computeIfAbsent(s.getOrderId(), k -> new LinkedHashMap<>()).put(s.getPlanId(), s.getPlanTitle());
        }
        Map<UUID, OrderContextView> out = new LinkedHashMap<>();
        for (Order o : orders) {
            List<OrderItem> its = sortItems(itemsByOrder.getOrDefault(o.getId(), List.of()));
            List<ItemRef> refs = new ArrayList<>();
            Map<UUID, List<PetRef>> petsByItem = new LinkedHashMap<>();
            for (int i = 0; i < its.size(); i++) {
                OrderItem it = its.get(i);
                refs.add(new ItemRef(it.getId(), it.getPlanId(), it.getSource(), i));
                petsByItem.put(it.getId(), bindingsByItem.getOrDefault(it.getId(), List.of()).stream()
                        .map(b -> pets.get(b.getPetId()))
                        .filter(java.util.Objects::nonNull)
                        .map(p -> new PetRef(p.petId(), p.name(), p.deleted())).toList());
            }
            out.put(o.getId(), new OrderContextView(o.getId(), o.getStatus(), o.getCreatedAt(), o.getOwnerId(),
                    o.getSitterId(), ownerNames.get(o.getOwnerId()), sitterNames.get(o.getSitterId()),
                    readable.contains(o.getId()), refs, petsByItem, titlesByOrder.getOrDefault(o.getId(), Map.of())));
        }
        return out;
    }

    /** BOOKING 依 group_index，MODIFICATION 依 created_at 接在其後。 */
    public static List<OrderItem> sortItems(List<OrderItem> items) {
        List<OrderItem> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing((OrderItem it) -> it.getSource() == OrderItemSource.BOOKING ? 0 : 1)
                .thenComparing(it -> it.getGroupIndex() == null ? 0 : it.getGroupIndex())
                .thenComparing(OrderItem::getCreatedAt)
                .thenComparing(OrderItem::getId));
        return sorted;
    }

    public OrderParticipants getParticipants(UUID orderId) {
        Order o = orderRepository.findById(orderId).orElseThrow(OrderException::notFound);
        return new OrderParticipants(o.getOwnerId(), o.getSitterId(), o.getStatus());
    }

    public boolean hasAnyOrderBetween(UUID sitterId, UUID ownerId) {
        return orderRepository.existsBySitterIdAndOwnerId(sitterId, ownerId);
    }

    public boolean hasActiveOrder(UUID userId) {
        return orderRepository.existsActiveForParty(userId, TERMINAL);
    }

    public boolean hasActiveOrderBetween(UUID sitterId, UUID ownerId) {
        return orderRepository.existsActiveBetween(sitterId, ownerId, TERMINAL);
    }

    public boolean hasActiveOrderWithPet(UUID petId) {
        return orderRepository.existsActiveWithPet(petId);
    }

    /** PRD-027 BR-3：CANCELLED、cancelled_by=SITTER、有 ORDER_REJECTED log。 */
    public boolean isDeclinedBySitter(UUID orderId, UUID sitterId) {
        return orderRepository.findById(orderId)
                .filter(o -> o.getSitterId().equals(sitterId))
                .filter(o -> o.getStatus() == OrderStatus.CANCELLED && o.getCancelledBy() == ActorRole.SITTER)
                .map(o -> orderLogRepository.existsByOrderIdAndActionType(orderId, "ORDER_REJECTED"))
                .orElse(false);
    }

    public boolean isRegularOwner(UUID ownerId, UUID sitterId) {
        return orderRepository.existsCompletedBetween(ownerId, sitterId);
    }

    public int countConfirmedInMonth(UUID sitterId, YearMonth monthTaipei) {
        return Math.toIntExact(orderRepository.countConfirmedBetween(sitterId, OrderClock.startOfMonth(monthTaipei),
                OrderClock.startOfMonth(monthTaipei.plusMonths(1))));
    }

    public int getNetPaid(UUID orderId) {
        if (!orderRepository.existsById(orderId)) {
            throw OrderException.notFound();
        }
        return paymentRepository.netPaid(orderId);
    }

    public Optional<MediaQuotaSnapshot> getMediaQuota(UUID orderId) {
        return orderSnapshotRepository.findByOrderId(orderId)
                .map(s -> new MediaQuotaSnapshot(s.getPlanTier(), s.getMaxPhotos(), s.getMaxVideos(), s.getMediaRetentionDays()));
    }

    /** M32：COMPLETED、尚未提醒、{@code completed_at + media_retention_days} 落在 [from, to)。 */
    public List<UUID> findCompletedOrdersPendingMediaExpiryWarning(OffsetDateTime from, OffsetDateTime to, int limit) {
        return orderRepository.findCompletedPendingMediaExpiryWarning(from, to, limit);
    }

    /** M32 協調者用：取當事人與結案時間 / 保留天數（一次查詢）。 */
    public Map<UUID, MediaExpiryView> getMediaExpiryViews(Set<UUID> orderIds) {
        Map<UUID, MediaExpiryView> out = new HashMap<>();
        Set<UUID> ids = new HashSet<>(orderIds);
        for (Order o : orderRepository.findAllById(ids)) {
            orderSnapshotRepository.findByOrderId(o.getId()).ifPresent(s -> out.put(o.getId(),
                    new MediaExpiryView(o.getId(), o.getOwnerId(), o.getCompletedAt(), s.getMediaRetentionDays())));
        }
        return out;
    }

    public record MediaExpiryView(UUID orderId, UUID ownerId, OffsetDateTime completedAt, int mediaRetentionDays) {
    }

    /** ORDER_REJECTED 的 log（PRD-010 轉介前置條件）。 */
    List<OrderLog> logsOf(UUID orderId) {
        return orderLogRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId);
    }
}
