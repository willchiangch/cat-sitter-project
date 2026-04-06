package com.catsitter.api.dto.sitter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CreateServiceRequest(
    @NotBlank String name,
    @NotNull @Positive BigDecimal basePrice,
    @NotNull @Positive Integer durationMinutes,
    @NotEmpty List<String> supportedPetTypes,
    LocalDate bookableStartDate,
    LocalDate bookableEndDate,
    LocalDate effectiveStartDate,
    LocalDate effectiveEndDate
) {}
