package com.petsitter.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * {@code credentialJson} 是前端 {@code navigator.credentials.create()}／{@code get()} 結果轉換後的
 * WebAuthn JSON（PublicKeyCredentialJSON 格式字串），原封不動交給
 * {@code PublicKeyCredential.parseRegistrationResponseJson}／{@code parseAssertionResponseJson} 解析。
 */
@Data
public class WebAuthnVerifyRequest {
    @NotBlank(message = "credentialJson 不能為空")
    private String credentialJson;
}
