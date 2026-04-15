package com.catsitter.api.dto.sitter;

import java.util.List;
import java.util.UUID;

public record BookingPreviewResponse(
    SitterPublicProfile sitterProfile,
    List<com.catsitter.api.dto.sitter.ServicePlanResponse> services,
    List<com.catsitter.api.dto.sitter.QuestionItemResponse> questionnaire
) {
    public record SitterPublicProfile(
        UUID profileId,
        String name,
        String slug,
        String avatarUrl,
        String bioSummary,
        java.util.List<String> serviceAreas,
        java.util.List<String> professionalLabels
    ) {}
}
