package com.petsitter.application.dto.order;

import java.time.OffsetDateTime;
import java.util.UUID;

/** E1 response 201。 */
public record CreateOrderResponse(UUID orderId, String status, int baseAmount, OffsetDateTime reviewExpiresAt,
                                  String questionnaireStatus) {
}
