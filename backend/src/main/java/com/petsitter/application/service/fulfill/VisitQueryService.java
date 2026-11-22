package com.petsitter.application.service.fulfill;

import com.petsitter.domain.model.order.OrderStatus;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** SD-FULFILL 的行程查詢介面（SD-ORDER §2.4.2）。 */
public interface VisitQueryService {

    List<VisitView> listVisits(UUID orderId);

    /**
     * 列表 / 帳本批次查詢：各訂單有效行程（status ≠ CANCELLED）的首末日，一次 GROUP BY 查詢（不 N+1）。
     * 無有效行程的訂單不在回傳 Map 內，由呼叫端決定 fallback。
     */
    Map<UUID, ServiceDateRange> effectiveServiceDateRanges(Collection<UUID> orderIds);

    List<UUID> findOrderIdsWithZombieVisits(LocalDate maxServiceDate, Set<OrderStatus> orderStatuses, int limit,
                                            CronScope scope);
}
