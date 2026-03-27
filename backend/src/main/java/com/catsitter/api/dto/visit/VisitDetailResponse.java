package com.catsitter.api.dto.visit;

import com.catsitter.api.entity.enums.VisitStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record VisitDetailResponse(
    UUID id,
    UUID orderId,
    OffsetDateTime visitStartTime,
    OffsetDateTime visitEndTime,
    VisitStatus status,
    String sitterNotes,
    List<ChecklistItemResponse> items
) {
    public record ChecklistItemResponse(
        UUID id,
        String serviceType,
        String description,
        Boolean isCompleted,
        String photoUrl,
        OffsetDateTime completedAt
    ) {}
}
