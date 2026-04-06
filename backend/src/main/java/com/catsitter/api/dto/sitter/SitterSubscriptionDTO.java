package com.catsitter.api.dto.sitter;

import java.time.LocalDate;

public record SitterSubscriptionDTO(
    String planId,    // plan_code e.g. "PRO"
    String status,    // "ACTIVE", "CANCELLED", "EXPIRED"
    LocalDate renewsAt
) {}
