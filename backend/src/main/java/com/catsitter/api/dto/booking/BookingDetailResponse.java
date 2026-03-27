package com.catsitter.api.dto.booking;

import com.catsitter.api.entity.enums.OrderStatus;
import com.catsitter.api.entity.enums.PaymentStatus;
import com.catsitter.api.entity.enums.QuestionnaireStatus;
import com.catsitter.api.entity.enums.VisitStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record BookingDetailResponse(
    UUID id,
    UUID clientProfileId,
    UUID sitterProfileId,
    ServiceDetail service,
    BigDecimal totalAmount,
    OrderStatus orderStatus,
    PaymentStatus paymentStatus,
    QuestionnaireStatus questionnaireStatus,
    List<VisitDetail> visits,
    List<AnswerDetail> answers
) {
    public record ServiceDetail(
        UUID id,
        String name,
        BigDecimal unitPrice
    ) {}

    public record VisitDetail(
        UUID id,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        VisitStatus status
    ) {}

    public record AnswerDetail(
        UUID questionId,
        String questionText,
        String answer
    ) {}
}
