package com.catsitter.api.dto.sitter;

import java.util.List;

public record BookingPreviewResponse(
    SitterPublicProfile sitterProfile,
    List<com.catsitter.api.dto.sitter.ServicePlanResponse> services,
    List<com.catsitter.api.dto.sitter.QuestionItemResponse> questionnaire
) {
    public record SitterPublicProfile(
        String name,
        String avatarUrl,
        String bioSummary,
        java.util.List<String> serviceAreas
    ) {}
}
