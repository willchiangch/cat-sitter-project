package com.catsitter.api.dto.booking;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record CreateBookingRequest(
    UUID sitterProfileId,
    UUID serviceId,
    List<UUID> petIds,
    List<VisitRequest> visits,
    List<AnswerRequest> answers
) {
    public record VisitRequest(
        OffsetDateTime startTime,
        OffsetDateTime endTime
    ) {}

    public record AnswerRequest(
        UUID questionId,
        String answer
    ) {}
}
