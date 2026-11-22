package com.petsitter.application.service.fulfill;

import com.petsitter.domain.model.fulfill.VisitServiceReport;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.repository.fulfill.VisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** SD-ORDER §2.4.2 / SD-FULFILL §2.3.1 的行程查詢介面實作（{@code SUPPORTS}，不 N+1）。 */
@Service
@RequiredArgsConstructor
public class VisitQueryServiceImpl implements VisitQueryService {

    private final VisitRepository visitRepository;

    /** 一次查詢（LEFT JOIN 日誌）；{@code careLogSubmitted} = 日誌 SUBMITTED。 */
    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<VisitView> listVisits(UUID orderId) {
        return visitRepository.findRowsWithLog(orderId).stream()
                .map(r -> new VisitView(r.getVisitId(), r.getOrderItemId(), r.getPlanId(), r.getServiceDate(), r.getStatus(),
                        r.getCompletedAt(), r.getClosedAt(), VisitServiceReport.SUBMITTED.equals(r.getCareLogStatus())))
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<UUID> findOrderIdsWithZombieVisits(LocalDate maxServiceDate, Set<OrderStatus> orderStatuses, int limit,
                                                   CronScope scope) {
        return visitRepository.findZombieOrderIds(maxServiceDate, orderStatuses.stream().map(Enum::name).toList(),
                scope.orderId(), limit);
    }
}
