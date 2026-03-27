package com.catsitter.api.dto.sitter;

import java.util.List;

public record BookingPreviewResponse(
    SitterPublicProfile sitter,
    List<ServicePlanResponse> services,
    List<QuestionItemResponse> questionnaire
) {
    public record SitterPublicProfile(
        String name,
        String avatarUrl,
        String bioSummary,
        List<String> serviceAreas
    ) {}
}
