package com.petsitter.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class RegisterRequest {
    @Email(message = "電子郵件格式不正確")
    @NotBlank(message = "電子郵件不能為空")
    private String email;

    @NotBlank(message = "密碼不能為空")
    private String password;

    @NotBlank(message = "姓名不能為空")
    private String fullName;

    @NotBlank(message = "角色不能為空")
    @Pattern(regexp = "^(OWNER|SITTER|ADMIN)$", message = "角色必須是 OWNER, SITTER 或 ADMIN")
    private String role;
}
