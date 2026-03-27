package com.catsitter.api.dto.sitter;

import com.catsitter.api.entity.enums.TargetPetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpdateQuestionRequest(
    @NotNull TargetPetType targetPetType,
    @NotBlank String questionText,
    @NotNull Integer sortOrder,
    @NotNull Boolean isActive
) {}
