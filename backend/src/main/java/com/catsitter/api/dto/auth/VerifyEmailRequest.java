package com.catsitter.api.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record VerifyEmailRequest(
    @NotBlank
    String code
) {}
