package com.catsitter.api.dto.sitter;

import java.util.List;
import java.util.UUID;

public record SitterProfileResponse(
    UUID profileId,
    String name,
    String avatarUrl,
    String phone,
    List<String> serviceAreas,
    String bioSummary,
    Boolean isVerified,
    String idCardFrontUrl,
    String facePhotoUrl,
    List<String> professionalLabels,
    String bankCode,
    String bankAccount,
    String bankAccountHolder,
    Integer notificationDaysBefore,
    String slug
) {}
