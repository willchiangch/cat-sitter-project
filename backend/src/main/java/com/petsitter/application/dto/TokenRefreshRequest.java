package com.petsitter.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TokenRefreshRequest {
    @NotBlank(message = "refreshToken 不得為空")
    private String refreshToken;
}
