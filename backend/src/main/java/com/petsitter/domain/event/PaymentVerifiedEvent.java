package com.petsitter.domain.event;

import java.util.UUID;

public record PaymentVerifiedEvent(UUID orderId, UUID sitterId) {
}
