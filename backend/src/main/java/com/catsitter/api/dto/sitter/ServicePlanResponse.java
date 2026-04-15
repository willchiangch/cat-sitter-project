package com.catsitter.api.dto.sitter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ServicePlanResponse(
    UUID serviceId,
    String name,
    BigDecimal basePrice,
    Integer durationMinutes,
    List<String> supportedPetTypes,
    Boolean isActive,
    LocalDate bookableStartDate,
    LocalDate bookableEndDate,
    LocalDate effectiveStartDate,
    LocalDate effectiveEndDate,
    String description,
    Boolean isWhitelistOnly
) {}
