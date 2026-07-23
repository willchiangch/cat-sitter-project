package com.petsitter.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class VerifyRegistrationOtpRequest {
    @Email(message = "電子郵件格式不正確")
    @NotBlank(message = "電子郵件不能為空")
    private String email;

    @NotBlank(message = "驗證碼不能為空")
    @Pattern(regexp = "^\\d{6}$", message = "驗證碼須為 6 碼數字")
    private String otpCode;
}
