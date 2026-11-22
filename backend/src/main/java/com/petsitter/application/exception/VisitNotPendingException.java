package com.petsitter.application.exception;

import java.util.Set;
import java.util.UUID;

/** SD-FULFILL {@code cancelVisits} 任一行程非 PENDING（整批不改）；SD-ORDER 轉為 409 {@code VISIT_ALREADY_STARTED}。 */
public class VisitNotPendingException extends RuntimeException {

    private final Set<UUID> visitIds;

    public VisitNotPendingException(Set<UUID> visitIds) {
        super("visits not pending");
        this.visitIds = Set.copyOf(visitIds);
    }

    public Set<UUID> getVisitIds() {
        return visitIds;
    }
}
