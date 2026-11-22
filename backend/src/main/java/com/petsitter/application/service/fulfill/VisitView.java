package com.petsitter.application.service.fulfill;

import com.petsitter.domain.model.order.VisitStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** {@code VisitQueryService.listVisits} 的唯讀檢視（SD-ORDER §2.4.2）。 */
public record VisitView(UUID visitId, UUID orderItemId, UUID planId, LocalDate serviceDate, VisitStatus status,
                        OffsetDateTime completedAt, OffsetDateTime closedAt, boolean careLogSubmitted) {

    public boolean effective() {
        return status != VisitStatus.CANCELLED;
    }

    public boolean finished() {
        return status == VisitStatus.DONE || status == VisitStatus.CLOSED_BY_SYSTEM;
    }
}
