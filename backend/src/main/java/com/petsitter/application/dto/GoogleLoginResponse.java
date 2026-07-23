package com.petsitter.application.dto;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class GoogleLoginResponse {
    private String status; // "NEEDS_ROLE_SELECTION" | "SUCCESS"

    // NEEDS_ROLE_SELECTION 階段使用
    private String email;
    private String fullName;

    // SUCCESS 階段使用
    private String accessToken;
    private String refreshToken;
    private UUID userId;
    private String role;
}
