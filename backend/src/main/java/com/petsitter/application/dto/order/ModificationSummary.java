package com.petsitter.application.dto.order;

import java.time.OffsetDateTime;
import java.util.UUID;

/** E18 每筆（created_at 倒序）。 */
public record ModificationSummary(UUID modificationId, String type, String status, String initiatedBy, String reason,
                                  Integer newTotalAmount, OffsetDateTime createdAt, OffsetDateTime endedAt) {
}
