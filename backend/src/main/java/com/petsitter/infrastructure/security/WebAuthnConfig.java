package com.petsitter.infrastructure.security;

import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.Set;

/**
 * PRD-000 AC-6：WebAuthn RelyingParty 設定。RP ID 取自 {@code app.frontend-base-url} 的 host
 * （本地開發為 localhost，正式環境隨 FRONTEND_BASE_URL 環境變數變動），origins 則為完整前端網址，
 * 兩者皆與既有的密碼重設連結、Google OAuth JS 來源共用同一組設定值，不另外新增環境變數。
 */
@Configuration
public class WebAuthnConfig {

    @Bean
    public RelyingParty relyingParty(
            @Value("${app.frontend-base-url}") String frontendBaseUrl,
            WebAuthnRpCredentialLookup credentialRepository) {
        URI frontendUri = URI.create(frontendBaseUrl);

        RelyingPartyIdentity identity = RelyingPartyIdentity.builder()
                .id(frontendUri.getHost())
                .name("WhiskerWatch")
                .build();

        return RelyingParty.builder()
                .identity(identity)
                .credentialRepository(credentialRepository)
                .origins(Set.of(frontendBaseUrl))
                .validateSignatureCounter(true)
                .build();
    }
}
