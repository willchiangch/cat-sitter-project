package com.catsitter.api.dto.sitter;

import com.catsitter.api.entity.enums.QuestionType;
import com.catsitter.api.entity.enums.TargetPetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreateQuestionRequest(
    @NotNull TargetPetType targetPetType,
    @NotBlank String questionText,
    @NotNull QuestionType type,
    Boolean required,
    List<String> options,
    Integer sortOrder
) {}
