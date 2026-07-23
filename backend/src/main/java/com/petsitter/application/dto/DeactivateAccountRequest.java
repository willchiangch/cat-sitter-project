package com.petsitter.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DeactivateAccountRequest {
    @NotBlank(message = "密碼不能為空")
    private String password;
}
