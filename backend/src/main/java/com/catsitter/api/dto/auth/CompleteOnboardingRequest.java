package com.catsitter.api.dto.auth;

import com.catsitter.api.entity.enums.RoleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CompleteOnboardingRequest(
    @NotNull RoleType roleType,
    @NotBlank String displayName
) {
}
