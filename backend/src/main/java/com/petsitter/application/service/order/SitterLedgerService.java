package com.petsitter.application.service.order;

import com.petsitter.application.dto.order.ModificationResponses;
import com.petsitter.application.port.UserDisplayQuery;
import com.petsitter.application.service.fulfill.ServiceDateRange;
import com.petsitter.application.service.fulfill.VisitQueryService;
import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.model.order.Payment;
import com.petsitter.domain.model.order.PaymentDirection;
import com.petsitter.domain.model.order.PaymentStatus;
import com.petsitter.domain.repository.order.OrderRepository;
import com.petsitter.domain.repository.order.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * E30 保母帳務總覽（SD-ORDER §6.3 E30；PRD-009 BR-22、baseline §4）：收入 = 該月（Asia/Taipei）進入 COMPLETED / CANCELLED
 * 的訂單已收淨額，由 payments 加總、不讀 total_amount；orders[] 排除淨額 0 的 CANCELLED（不影響 totalIncome）。
 */
@Service
@RequiredArgsConstructor
public class SitterLedgerService {

    private final OrderCommandSupport support;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final VisitQueryService visitQueryService;
    private final UserDisplayQuery userDisplayQuery;
    private final OrderClock clock;

    public ModificationResponses.Ledger ledger(String month) {
        CurrentActor actor = CurrentActor.fromToken();
        YearMonth ym = OrderDetailService.parseMonth(month, "month");
        return support.readOnly(() -> {
            List<Order> orders = orderRepository.findTerminalForSitterBetween(actor.userId(), OrderClock.startOfMonth(ym),
                    OrderClock.startOfMonth(ym.plusMonths(1)));
            Set<UUID> ids = orders.stream().map(Order::getId).collect(Collectors.toSet());
            Map<UUID, Integer> net = ids.isEmpty() ? Map.of() : paymentRepository.findByOrderIdIn(ids).stream()
                    .filter(p -> p.getStatus() == PaymentStatus.VERIFIED)
                    .collect(Collectors.groupingBy(Payment::getOrderId, Collectors.summingInt(
                            p -> p.getDirection() == PaymentDirection.CHARGE ? p.getAmount() : -p.getAmount())));
            Map<UUID, String> names = orders.isEmpty() ? Map.of()
                    : userDisplayQuery.ownerDisplayNames(orders.stream().map(Order::getOwnerId).collect(Collectors.toSet()));
            Map<UUID, ServiceDateRange> ranges = visitQueryService.effectiveServiceDateRanges(ids);
            int total = 0;
            List<ModificationResponses.LedgerOrder> rows = new ArrayList<>();
            for (Order o : orders) {
                int n = net.getOrDefault(o.getId(), 0);
                total += n;
                if (o.getStatus() == OrderStatus.CANCELLED && n == 0) {
                    continue;
                }
                ServiceDateRange range = ranges.get(o.getId());
                rows.add(new ModificationResponses.LedgerOrder(o.getId(), o.getStatus().name(), names.get(o.getOwnerId()),
                        range == null ? o.getFirstServiceDate() : range.first(),
                        range == null ? o.getFirstServiceDate() : range.last(),
                        OrderDetailService.terminalAt(o), n));
            }
            return new ModificationResponses.Ledger(ym.toString(), clock.monthTaipei().toString(), total, rows);
        });
    }
}
