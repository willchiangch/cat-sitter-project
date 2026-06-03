package com.petsitter.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateSitterPaymentInfoRequest(
    @NotBlank(message = "銀行代碼不得為空") 
    @Pattern(regexp = "^\\d{3}$", message = "銀行代碼必須為 3 碼數字") 
    String bankCode,
    
    @NotBlank(message = "分行名稱不得為空") 
    @Size(max = 100, message = "分行名稱長度上限為 100 字元") 
    String bankBranch,
    
    @NotBlank(message = "銀行帳號不得為空") 
    @Pattern(regexp = "^\\d{10,16}$", message = "銀行帳號必須為 10 到 16 碼數字") 
    String bankAccount,
    
    @NotBlank(message = "戶名不得為空") 
    @Size(max = 100, message = "戶名長度上限為 100 字元") 
    String bankPayeeName
) {}
