package com.petsitter.application.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.petsitter.application.exception.GoogleAuthException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.GeneralSecurityException;
import java.util.Collections;

/**
 * PRD-000 AC-5：驗證 Google Identity Services 前端回傳的 ID Token（簽章/exp/iss/aud），
 * 委由官方 GoogleIdTokenVerifier 處理 Google 公鑰 JWKS 抓取與輪替，避免手刻 JWT 驗證的安全風險。
 */
@Slf4j
@Service
public class GoogleTokenVerifierService {

    private final GoogleIdTokenVerifier verifier;

    public GoogleTokenVerifierService(@Value("${app.google.client-id}") String clientId) {
        this.verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(clientId))
                .build();
    }

    public GoogleUserInfo verify(String idTokenString) {
        GoogleIdToken idToken;
        try {
            idToken = verifier.verify(idTokenString);
        } catch (GeneralSecurityException | java.io.IOException | IllegalArgumentException e) {
            log.warn("Google ID Token 驗證發生例外", e);
            throw new GoogleAuthException(HttpStatus.UNAUTHORIZED, "GOOGLE_TOKEN_INVALID", "Google 登入驗證失敗");
        }

        if (idToken == null) {
            throw new GoogleAuthException(HttpStatus.UNAUTHORIZED, "GOOGLE_TOKEN_INVALID", "Google 登入驗證失敗");
        }

        GoogleIdToken.Payload payload = idToken.getPayload();
        String fullName = (String) payload.get("name");
        return new GoogleUserInfo(payload.getEmail(), fullName, Boolean.TRUE.equals(payload.getEmailVerified()));
    }
}
