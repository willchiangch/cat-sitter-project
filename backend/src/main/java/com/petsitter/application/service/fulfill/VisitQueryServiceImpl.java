package com.petsitter.application.service.fulfill;

import com.petsitter.domain.model.fulfill.Visit;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.repository.fulfill.VisitRepository;
import com.petsitter.domain.repository.fulfill.VisitServiceReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** SD-FULFILL §2.3.1 查詢介面的 B 階段最小實作。TODO(C 階段 F-M2)：改為單一 LEFT JOIN 查詢。 */
@Service
@RequiredArgsConstructor
public class VisitQueryServiceImpl implements VisitQueryService {

    private final VisitRepository visitRepository;
    private final VisitServiceReportRepository reportRepository;

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<VisitView> listVisits(UUID orderId) {
        List<Visit> visits = visitRepository.findByOrderIdOrderByServiceDateAscSequenceInDayAsc(orderId);
        Set<UUID> submitted = visits.isEmpty() ? Set.of()
                : new HashSet<>(reportRepository.findSubmittedVisitIds(visits.stream().map(Visit::getId).toList()));
        return visits.stream().map(v -> new VisitView(v.getId(), v.getOrderItemId(), v.getPlanId(), v.getServiceDate(),
                v.getStatus(), v.getCompletedAt(), v.getClosedAt(), submitted.contains(v.getId()))).toList();
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<UUID> findOrderIdsWithZombieVisits(LocalDate maxServiceDate, Set<OrderStatus> orderStatuses, int limit,
                                                   CronScope scope) {
        return visitRepository.findZombieOrderIds(maxServiceDate, orderStatuses.stream().map(Enum::name).toList(),
                scope.orderId(), limit);
    }
}
