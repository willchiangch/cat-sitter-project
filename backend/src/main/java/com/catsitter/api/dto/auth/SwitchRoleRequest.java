package com.catsitter.api.dto.auth;

import com.catsitter.api.entity.enums.RoleType;
import jakarta.validation.constraints.NotNull;

public record SwitchRoleRequest(
    @NotNull RoleType roleType
) {}
