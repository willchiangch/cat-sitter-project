package com.catsitter.api.dto.visit;

import com.catsitter.api.entity.enums.VisitStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record VisitSummaryResponse(
    UUID id,
    UUID orderId,
    String clientName,
    String serviceName,
    OffsetDateTime visitStartTime,
    OffsetDateTime visitEndTime,
    VisitStatus status,
    String petName,
    String petImageUrl
) {}
