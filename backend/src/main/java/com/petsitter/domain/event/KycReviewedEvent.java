package com.petsitter.domain.event;

import java.util.UUID;

public record KycReviewedEvent(UUID recordId, UUID sitterId, String status, String rejectReason) {
}
