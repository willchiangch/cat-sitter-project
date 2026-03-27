package com.catsitter.api.dto.auth;

public record AuthTokenResponse(
    String accessToken,
    String refreshToken,
    long expiresIn
) {}
