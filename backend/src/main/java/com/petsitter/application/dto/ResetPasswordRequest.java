package com.petsitter.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ResetPasswordRequest {
    @NotBlank(message = "重設token不能為空")
    private String token;

    @NotBlank(message = "新密碼不能為空")
    @Size(min = 8, message = "密碼長度至少需 8 碼")
    private String newPassword;
}
