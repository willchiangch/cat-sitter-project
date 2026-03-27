package com.catsitter.api.dto.auth;

import com.catsitter.api.entity.enums.RoleType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @Email @NotBlank String email,
    @NotBlank @Size(min = 8, max = 128) String password,
    @NotNull RoleType roleType,
    @NotBlank @Size(min = 1, max = 100) String displayName
) {}
