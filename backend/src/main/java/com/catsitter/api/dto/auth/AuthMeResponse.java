package com.catsitter.api.dto.auth;

import com.catsitter.api.entity.enums.RoleType;
import java.util.List;
import java.util.UUID;

public record AuthMeResponse(
    UUID accountId,
    String email,
    RoleType lastActiveRole,
    boolean emailVerified,
    List<ProfileSummary> profiles,
    String debugCode
) {
    public record ProfileSummary(
        UUID profileId,
        RoleType role,
        String name,
        String avatarUrl
    ) {}
}
