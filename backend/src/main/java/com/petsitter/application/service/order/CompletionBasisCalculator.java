package com.petsitter.application.service.order;

import com.petsitter.application.service.fulfill.VisitView;
import com.petsitter.domain.model.order.ModificationRequest;
import com.petsitter.domain.model.order.VisitStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code completion_basis_at}（baseline §5、SD-ORDER §3.1.3）：任何有效行程仍 PENDING / IN_PROGRESS → NULL；
 * 否則 max(DONE 的 {@code completed_at + CARE_LOG_EDIT_HOURS}、CLOSED_BY_SYSTEM 的關閉時間、最後一筆 SETTLED 變更的 {@code ended_at})。
 */
@Component
@RequiredArgsConstructor
public class CompletionBasisCalculator {

    private final OrderSettings settings;

    public OffsetDateTime compute(List<VisitView> visits, List<ModificationRequest> settled) {
        OffsetDateTime basis = null;
        boolean anyEffective = false;
        for (VisitView v : visits) {
            if (v.status() == VisitStatus.CANCELLED) {
                continue;
            }
            anyEffective = true;
            OffsetDateTime end;
            if (v.status() == VisitStatus.DONE) {
                end = v.completedAt().plus(settings.careLogEdit());
            } else if (v.status() == VisitStatus.CLOSED_BY_SYSTEM) {
                end = v.closedAt();
            } else {
                return null;
            }
            basis = basis == null ? end : OrderClock.max(basis, end);
        }
        if (!anyEffective) {
            return null;
        }
        for (ModificationRequest m : settled) {
            if (m.getEndedAt() != null) {
                basis = OrderClock.max(basis, m.getEndedAt());
            }
        }
        return basis;
    }
}
