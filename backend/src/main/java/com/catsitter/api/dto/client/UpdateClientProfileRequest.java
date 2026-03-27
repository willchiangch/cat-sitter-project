package com.catsitter.api.dto.client;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateClientProfileRequest(
    @NotBlank @Size(max = 100) String name,
    @Size(max = 1024) String avatarUrl,
    @Size(max = 50) String phone,
    @Size(max = 255) String address
) {}
