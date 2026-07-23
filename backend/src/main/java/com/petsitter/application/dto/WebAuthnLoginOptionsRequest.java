package com.petsitter.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WebAuthnLoginOptionsRequest {
    @NotBlank(message = "email 不能為空")
    private String email;
}
