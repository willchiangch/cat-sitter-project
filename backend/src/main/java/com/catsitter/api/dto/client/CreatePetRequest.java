package com.catsitter.api.dto.client;

import com.catsitter.api.entity.enums.PetGender;
import com.catsitter.api.entity.enums.PetSpecies;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record CreatePetRequest(
    @NotBlank String name,
    @NotNull PetSpecies species,
    PetGender gender,
    Boolean isNeutered,
    @PositiveOrZero BigDecimal weightKg,
    String avatarUrl,
    String medicalNotes,
    String dietaryNotes,
    String personalityNotes,
    String otherNotes
) {}
