package com.petsitter.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ResendOtpRequest {
    @Email(message = "電子郵件格式不正確")
    @NotBlank(message = "電子郵件不能為空")
    private String email;
}
