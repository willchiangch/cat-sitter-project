package com.petsitter.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WebAuthnLoginVerifyRequest {
    @NotBlank(message = "email 不能為空")
    private String email;

    @NotBlank(message = "credentialJson 不能為空")
    private String credentialJson;
}
