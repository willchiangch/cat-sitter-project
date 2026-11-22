package com.petsitter.application.dto.order;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** E31 response（SD-ORDER §6.3）。 */
public record BookingContextResponse(UUID sitterId, String bookability, List<Plan> plans, Questionnaire questionnaire,
                                     OffsetDateTime serverTime) {

    public record Plan(UUID planId, String title, int unitPrice, List<String> petTypes, LocalDate bookableFrom,
                       LocalDate bookableTo) {
    }

    public record Questionnaire(boolean required, String exemptReason, List<Question> questions) {
    }

    public record Question(UUID questionId, String text, String type, List<String> options, boolean required,
                           int sortOrder) {
    }
}
