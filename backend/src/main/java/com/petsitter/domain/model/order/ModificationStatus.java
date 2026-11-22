package com.petsitter.domain.model.order;

/** modification_requests.status（SD-ORDER §3.3）。 */
public enum ModificationStatus {
    REQUESTED, QUOTED, AWAITING_SUPPLEMENT, AWAITING_REFUND, SETTLED, REJECTED, WITHDRAWN, EXPIRED
}
