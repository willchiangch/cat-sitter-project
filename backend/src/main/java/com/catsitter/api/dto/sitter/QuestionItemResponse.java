package com.catsitter.api.dto.sitter;

import com.catsitter.api.entity.enums.QuestionType;
import com.catsitter.api.entity.enums.TargetPetType;

import java.util.List;
import java.util.UUID;

public record QuestionItemResponse(
    UUID questionId,
    TargetPetType targetPetType,
    String questionText,
    QuestionType type,
    Boolean required,
    List<String> options,
    Integer sortOrder,
    Boolean isActive
) {}
