package com.petsitter.domain.event;

import java.util.UUID;

public record PaymentProofSubmittedEvent(UUID orderId, UUID ownerId, String paymentProofUrl) {
}
