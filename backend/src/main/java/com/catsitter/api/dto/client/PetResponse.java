package com.catsitter.api.dto.client;

import com.catsitter.api.entity.enums.PetGender;
import com.catsitter.api.entity.enums.PetHealthStatus;
import com.catsitter.api.entity.enums.PetSpecies;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PetResponse(
    UUID petId,
    String name,
    PetSpecies species,
    PetGender gender,
    PetHealthStatus neuteredStatus,
    PetHealthStatus vaccinationStatus,
    PetHealthStatus dewormingStatus,
    BigDecimal weightKg,
    LocalDate birthDate,
    String avatarUrl,
    String medicalNotes,
    String dietaryNotes,
    String personalityNotes,
    String otherNotes
) {}
