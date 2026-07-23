package com.petsitter.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.petsitter.application.dto.AuthResponse;
import com.petsitter.application.exception.WebAuthnException;
import com.petsitter.domain.model.User;
import com.petsitter.domain.model.WebAuthnChallenge;
import com.petsitter.domain.model.WebAuthnCredential;
import com.petsitter.domain.repository.UserRepository;
import com.petsitter.domain.repository.WebAuthnChallengeRepository;
import com.petsitter.domain.repository.WebAuthnCredentialRepository;
import com.petsitter.infrastructure.security.WebAuthnUserHandleUtil;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AuthenticatorAssertionResponse;
import com.yubico.webauthn.data.AuthenticatorAttestationResponse;
import com.yubico.webauthn.data.ClientAssertionExtensionOutputs;
import com.yubico.webauthn.data.ClientRegistrationExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.RegistrationFailedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PRD-000 AC-6：生物辨識登入 (WebAuthn)。技術選型與序列圖見 SD-000 第 10 節。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebAuthnService {

    private static final long CHALLENGE_EXPIRY_MINUTES = 5;
    private static final String TYPE_REGISTRATION = "REGISTRATION";
    private static final String TYPE_AUTHENTICATION = "AUTHENTICATION";

    private final UserRepository userRepository;
    private final WebAuthnCredentialRepository webAuthnCredentialRepository;
    private final WebAuthnChallengeRepository webAuthnChallengeRepository;
    private final RelyingParty relyingParty;
    private final AuthService authService;
    private final AuditLogService auditLogService;

    @Transactional
    public String startRegistration(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("找不到使用者資訊"));

        UserIdentity userIdentity = UserIdentity.builder()
                .name(user.getEmail())
                .displayName(user.getFullName() != null && !user.getFullName().isBlank() ? user.getFullName() : user.getEmail())
                .id(WebAuthnUserHandleUtil.toUserHandle(user.getId()))
                .build();

        PublicKeyCredentialCreationOptions options = relyingParty.startRegistration(
                StartRegistrationOptions.builder().user(userIdentity).build());

        try {
            webAuthnChallengeRepository.deleteByUserIdAndChallengeType(userId, TYPE_REGISTRATION);
            webAuthnChallengeRepository.save(WebAuthnChallenge.builder()
                    .userId(userId)
                    .challengeType(TYPE_REGISTRATION)
                    .requestJson(options.toJson())
                    .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(CHALLENGE_EXPIRY_MINUTES))
                    .build());

            return options.toCredentialsCreateJson();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("WebAuthn 註冊選項序列化失敗", e);
        }
    }

    @Transactional
    public void finishRegistration(UUID userId, String credentialJson) {
        WebAuthnChallenge challenge = webAuthnChallengeRepository
                .findTopByUserIdAndChallengeTypeOrderByCreatedAtDesc(userId, TYPE_REGISTRATION)
                .orElseThrow(() -> new WebAuthnException(HttpStatus.BAD_REQUEST, "WEBAUTHN_CHALLENGE_EXPIRED", "註冊挑戰已過期，請重新開始"));

        if (challenge.getExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            webAuthnChallengeRepository.delete(challenge);
            throw new WebAuthnException(HttpStatus.BAD_REQUEST, "WEBAUTHN_CHALLENGE_EXPIRED", "註冊挑戰已過期，請重新開始");
        }

        try {
            PublicKeyCredentialCreationOptions request = PublicKeyCredentialCreationOptions.fromJson(challenge.getRequestJson());
            PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> response =
                    PublicKeyCredential.parseRegistrationResponseJson(credentialJson);

            RegistrationResult result = relyingParty.finishRegistration(FinishRegistrationOptions.builder()
                    .request(request)
                    .response(response)
                    .build());

            webAuthnCredentialRepository.save(WebAuthnCredential.builder()
                    .userId(userId)
                    .credentialId(result.getKeyId().getId().getBase64Url())
                    .publicKeyCose(result.getPublicKeyCose().getBase64Url())
                    .signCount(result.getSignatureCount())
                    .build());

            webAuthnChallengeRepository.delete(challenge);
            auditLogService.writeUserActionLog("AUTH_WEBAUTHN_REGISTER", "CREATE", userId, userId, "webauthn_credentials");
        } catch (RegistrationFailedException | IOException e) {
            log.warn("[WebAuthnService] Registration verification failed for user {}: {}", userId, e.getMessage());
            throw new WebAuthnException(HttpStatus.BAD_REQUEST, "WEBAUTHN_REGISTRATION_FAILED", "生物辨識裝置註冊失敗，請重新嘗試");
        }
    }

    @Transactional
    public String startLogin(String email) {
        AssertionRequest request = relyingParty.startAssertion(StartAssertionOptions.builder()
                .username(email)
                .build());

        try {
            // 查無使用者時仍回傳合法但 allowCredentials 為空的回應，不透過狀態碼差異洩漏帳號是否存在
            Optional<User> user = userRepository.findByEmail(email);
            if (user.isPresent()) {
                UUID userId = user.get().getId();
                webAuthnChallengeRepository.deleteByUserIdAndChallengeType(userId, TYPE_AUTHENTICATION);
                webAuthnChallengeRepository.save(WebAuthnChallenge.builder()
                        .userId(userId)
                        .challengeType(TYPE_AUTHENTICATION)
                        .requestJson(request.toJson())
                        .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(CHALLENGE_EXPIRY_MINUTES))
                        .build());
            }

            return request.toCredentialsGetJson();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("WebAuthn 登入選項序列化失敗", e);
        }
    }

    @Transactional
    public AuthResponse finishLogin(String email, String credentialJson) {
        User user = userRepository.findByEmail(email)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new BadCredentialsException("帳號或密碼錯誤"));

        WebAuthnChallenge challenge = webAuthnChallengeRepository
                .findTopByUserIdAndChallengeTypeOrderByCreatedAtDesc(user.getId(), TYPE_AUTHENTICATION)
                .orElseThrow(() -> new WebAuthnException(HttpStatus.UNAUTHORIZED, "WEBAUTHN_LOGIN_FAILED", "生物辨識登入失敗，請重新嘗試"));

        if (challenge.getExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            webAuthnChallengeRepository.delete(challenge);
            throw new WebAuthnException(HttpStatus.UNAUTHORIZED, "WEBAUTHN_LOGIN_FAILED", "生物辨識登入失敗，請重新嘗試");
        }

        try {
            AssertionRequest request = AssertionRequest.fromJson(challenge.getRequestJson());
            PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> response =
                    PublicKeyCredential.parseAssertionResponseJson(credentialJson);

            AssertionResult result = relyingParty.finishAssertion(FinishAssertionOptions.builder()
                    .request(request)
                    .response(response)
                    .build());

            if (!result.isSuccess()) {
                throw new WebAuthnException(HttpStatus.UNAUTHORIZED, "WEBAUTHN_LOGIN_FAILED", "生物辨識登入失敗，請重新嘗試");
            }

            List<WebAuthnCredential> matched = webAuthnCredentialRepository.findByUserId(user.getId());
            for (WebAuthnCredential credential : matched) {
                if (credential.getCredentialId().equals(response.getId().getBase64Url())) {
                    credential.setSignCount(result.getSignatureCount());
                    credential.setLastUsedAt(OffsetDateTime.now(ZoneOffset.UTC));
                    webAuthnCredentialRepository.save(credential);
                    break;
                }
            }

            webAuthnChallengeRepository.delete(challenge);
            auditLogService.writeUserActionLog("AUTH_LOGIN", "LOGIN", user.getId(), user.getId(), "users");

            return authService.createAuthResponse(user);
        } catch (AssertionFailedException | IOException e) {
            log.warn("[WebAuthnService] Assertion verification failed for user {}: {}", user.getId(), e.getMessage());
            throw new WebAuthnException(HttpStatus.UNAUTHORIZED, "WEBAUTHN_LOGIN_FAILED", "生物辨識登入失敗，請重新嘗試");
        }
    }

    public List<WebAuthnCredential> listCredentials(UUID userId) {
        return webAuthnCredentialRepository.findByUserId(userId);
    }

    @Transactional
    public void deleteCredential(UUID userId, UUID credentialRecordId) {
        WebAuthnCredential credential = webAuthnCredentialRepository.findById(credentialRecordId)
                .orElseThrow(() -> new WebAuthnException(HttpStatus.NOT_FOUND, "WEBAUTHN_CREDENTIAL_NOT_FOUND", "找不到該生物辨識裝置"));
        if (!credential.getUserId().equals(userId)) {
            throw new WebAuthnException(HttpStatus.FORBIDDEN, "FORBIDDEN", "無權刪除他人的生物辨識裝置");
        }
        webAuthnCredentialRepository.delete(credential);
    }
}
