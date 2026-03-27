package com.catsitter.api.dto.sitter;

import com.catsitter.api.entity.enums.TargetPetType;
import java.util.UUID;

public record QuestionItemResponse(
    UUID questionId,
    TargetPetType targetPetType,
    String questionText,
    Integer sortOrder,
    Boolean isActive
) {}
