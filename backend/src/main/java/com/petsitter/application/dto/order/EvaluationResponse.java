package com.petsitter.application.dto.order;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** E5 response。 */
public record EvaluationResponse(UUID orderId, String status, OffsetDateTime reviewExpiresAt, int baseAmount,
                                 Owner owner, boolean isRegularOwner, List<OrderDetailResponse.Group> groups,
                                 Questionnaire questionnaire, Precheck precheck, OffsetDateTime serverTime) {

    public record Owner(String displayName) {
    }

    public record Questionnaire(String status, String exemptReason, List<Answer> answers) {
    }

    public record Answer(UUID questionId, String questionText, String questionType, Object answer) {
    }

    public record Precheck(boolean subscriptionLocked, boolean quotaReached, boolean payoutAccountMissing,
                           boolean reviewExpired, boolean planAllowsAdjustment) {
    }
}
