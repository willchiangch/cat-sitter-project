package com.petsitter.domain.model.order;

/** visits.status（SD-FULFILL §3.1；ck_visits_status）。 */
public enum VisitStatus {
    PENDING, IN_PROGRESS, DONE, CLOSED_BY_SYSTEM, CANCELLED
}
