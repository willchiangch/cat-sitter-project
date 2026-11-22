package com.petsitter.application.service.fulfill;

import com.petsitter.domain.model.order.OrderStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** SD-FULFILL 的行程查詢介面（SD-ORDER §2.4.2）。 */
public interface VisitQueryService {

    List<VisitView> listVisits(UUID orderId);

    List<UUID> findOrderIdsWithZombieVisits(LocalDate maxServiceDate, Set<OrderStatus> orderStatuses, int limit,
                                            CronScope scope);
}
