package com.catsitter.api.dto.client;

import java.util.UUID;

public record ClientProfileResponse(
    UUID profileId,
    String name,
    String avatarUrl,
    String phone,
    String address
) {}
