package com.petsitter.domain.event;

import java.util.UUID;

public record PaymentRejectedEvent(UUID orderId, UUID sitterId, String rejectReason) {
}
