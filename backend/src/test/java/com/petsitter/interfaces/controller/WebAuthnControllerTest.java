package com.petsitter.interfaces.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petsitter.application.dto.WebAuthnLoginOptionsRequest;
import com.petsitter.application.dto.WebAuthnLoginVerifyRequest;
import com.petsitter.application.dto.WebAuthnVerifyRequest;
import com.petsitter.domain.model.User;
import com.petsitter.domain.model.WebAuthnChallenge;
import com.petsitter.domain.model.WebAuthnCredential;
import com.petsitter.domain.repository.UserRepository;
import com.petsitter.domain.repository.WebAuthnChallengeRepository;
import com.petsitter.domain.repository.WebAuthnCredentialRepository;
import com.petsitter.infrastructure.security.TokenContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("local")
@DisplayName("PRD-000 AC-6: 生物辨識登入 (WebAuthn) 整合測試")
class WebAuthnControllerTest {

    static {
        System.setProperty("com.github.dockerjava.api.version", "1.44");
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    private ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private WebAuthnCredentialRepository webAuthnCredentialRepository;

    @Autowired
    private WebAuthnChallengeRepository webAuthnChallengeRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = userRepository.save(User.builder()
                .email("webauthn-" + UUID.randomUUID() + "@test.com")
                .passwordHash(passwordEncoder.encode("password123"))
                .fullName("生物辨識測試使用者")
                .role("OWNER")
                .build());
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    @DisplayName("未登入呼叫 register/options 應回傳 401")
    void should_Return401_When_StartRegistration_NotAuthenticated() throws Exception {
        mockMvc.perform(post("/api/auth/webauthn/register/options"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("已登入呼叫 register/options 應回傳含 challenge/rp/user 的合法選項")
    void should_ReturnValidOptions_When_StartRegistration_Authenticated() throws Exception {
        TokenContext.setUserId(testUser.getId());

        mockMvc.perform(post("/api/auth/webauthn/register/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey.challenge").exists())
                .andExpect(jsonPath("$.publicKey.rp.id").exists())
                .andExpect(jsonPath("$.publicKey.user.name").value(testUser.getEmail()));
    }

    @Test
    @DisplayName("register/verify 帶入無效憑證 JSON 應回傳 400 WEBAUTHN_REGISTRATION_FAILED")
    void should_Return400_When_FinishRegistration_InvalidCredential() throws Exception {
        TokenContext.setUserId(testUser.getId());

        // 先正常呼叫一次 options，確保有有效的挑戰紀錄可比對
        mockMvc.perform(post("/api/auth/webauthn/register/options"))
                .andExpect(status().isOk());

        WebAuthnVerifyRequest request = new WebAuthnVerifyRequest();
        request.setCredentialJson("{\"not\":\"a valid webauthn credential\"}");

        mockMvc.perform(post("/api/auth/webauthn/register/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("WEBAUTHN_REGISTRATION_FAILED"));
    }

    @Test
    @DisplayName("register/verify 挑戰已過期應回傳 400 WEBAUTHN_CHALLENGE_EXPIRED")
    void should_Return400_When_FinishRegistration_ChallengeExpired() throws Exception {
        TokenContext.setUserId(testUser.getId());

        mockMvc.perform(post("/api/auth/webauthn/register/options"))
                .andExpect(status().isOk());

        WebAuthnChallenge challenge = webAuthnChallengeRepository
                .findTopByUserIdAndChallengeTypeOrderByCreatedAtDesc(testUser.getId(), "REGISTRATION")
                .orElseThrow();
        challenge.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        webAuthnChallengeRepository.save(challenge);

        WebAuthnVerifyRequest request = new WebAuthnVerifyRequest();
        request.setCredentialJson("{\"irrelevant\":\"json\"}");

        mockMvc.perform(post("/api/auth/webauthn/register/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("WEBAUTHN_CHALLENGE_EXPIRED"));
    }

    @Test
    @DisplayName("login/options 對不存在的 Email 仍回傳合法但 allowCredentials 為空的回應，不洩漏帳號存在性")
    void should_ReturnEmptyAllowCredentials_When_StartLogin_UnknownEmail() throws Exception {
        WebAuthnLoginOptionsRequest request = new WebAuthnLoginOptionsRequest();
        request.setEmail("no-such-user-" + UUID.randomUUID() + "@test.com");

        mockMvc.perform(post("/api/auth/webauthn/login/options")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey.challenge").exists())
                .andExpect(jsonPath("$.publicKey.allowCredentials").isEmpty());
    }

    @Test
    @DisplayName("login/options 對已存在但尚未註冊生物辨識的 Email 應回傳空 allowCredentials")
    void should_ReturnEmptyAllowCredentials_When_StartLogin_NoCredentialsRegistered() throws Exception {
        WebAuthnLoginOptionsRequest request = new WebAuthnLoginOptionsRequest();
        request.setEmail(testUser.getEmail());

        mockMvc.perform(post("/api/auth/webauthn/login/options")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey.allowCredentials").isEmpty());
    }

    @Test
    @DisplayName("login/options 對已註冊生物辨識裝置的 Email 應回傳非空 allowCredentials")
    void should_ReturnAllowCredentials_When_StartLogin_HasRegisteredCredential() throws Exception {
        webAuthnCredentialRepository.save(WebAuthnCredential.builder()
                .userId(testUser.getId())
                .credentialId("fake-credential-id-" + UUID.randomUUID())
                .publicKeyCose("ZmFrZS1jb3NlLWtleQ") // 任意合法 base64url 字串即可，本測試不驗證簽章
                .signCount(0)
                .build());

        WebAuthnLoginOptionsRequest request = new WebAuthnLoginOptionsRequest();
        request.setEmail(testUser.getEmail());

        mockMvc.perform(post("/api/auth/webauthn/login/options")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey.allowCredentials", org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    @DisplayName("login/verify 帶入無效憑證 JSON 應回傳 401 WEBAUTHN_LOGIN_FAILED")
    void should_Return401_When_FinishLogin_InvalidCredential() throws Exception {
        WebAuthnLoginOptionsRequest optionsRequest = new WebAuthnLoginOptionsRequest();
        optionsRequest.setEmail(testUser.getEmail());
        mockMvc.perform(post("/api/auth/webauthn/login/options")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(optionsRequest)))
                .andExpect(status().isOk());

        WebAuthnLoginVerifyRequest verifyRequest = new WebAuthnLoginVerifyRequest();
        verifyRequest.setEmail(testUser.getEmail());
        verifyRequest.setCredentialJson("{\"not\":\"a valid webauthn assertion\"}");

        mockMvc.perform(post("/api/auth/webauthn/login/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("WEBAUTHN_LOGIN_FAILED"));
    }

    @Test
    @DisplayName("login/verify 查無對應挑戰紀錄應回傳 401 WEBAUTHN_LOGIN_FAILED（未曾呼叫 options）")
    void should_Return401_When_FinishLogin_NoChallengeRequested() throws Exception {
        WebAuthnLoginVerifyRequest verifyRequest = new WebAuthnLoginVerifyRequest();
        verifyRequest.setEmail(testUser.getEmail());
        verifyRequest.setCredentialJson("{\"not\":\"a valid webauthn assertion\"}");

        mockMvc.perform(post("/api/auth/webauthn/login/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("WEBAUTHN_LOGIN_FAILED"));
    }

    @Test
    @DisplayName("已註銷帳號呼叫 login/verify 應回傳 401 帳號或密碼錯誤，不透露帳號已註銷")
    void should_Return401_When_FinishLogin_DeactivatedAccount() throws Exception {
        testUser.setDeleted(true);
        userRepository.save(testUser);

        WebAuthnLoginVerifyRequest verifyRequest = new WebAuthnLoginVerifyRequest();
        verifyRequest.setEmail(testUser.getEmail());
        verifyRequest.setCredentialJson("{\"not\":\"a valid webauthn assertion\"}");

        mockMvc.perform(post("/api/auth/webauthn/login/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("AUTH_FAILED"));
    }

    @Test
    @DisplayName("未登入查詢生物辨識裝置清單應回傳 401")
    void should_Return401_When_ListCredentials_NotAuthenticated() throws Exception {
        mockMvc.perform(get("/api/auth/webauthn/credentials"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("已登入查詢生物辨識裝置清單應回傳自己的所有裝置")
    void should_ReturnOwnCredentials_When_ListCredentials_Authenticated() throws Exception {
        webAuthnCredentialRepository.save(WebAuthnCredential.builder()
                .userId(testUser.getId())
                .credentialId("list-test-credential-" + UUID.randomUUID())
                .publicKeyCose("ZmFrZS1jb3NlLWtleQ")
                .signCount(0)
                .build());
        TokenContext.setUserId(testUser.getId());

        mockMvc.perform(get("/api/auth/webauthn/credentials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    @DisplayName("刪除不存在的憑證應回傳 404")
    void should_Return404_When_DeleteCredential_NotFound() throws Exception {
        TokenContext.setUserId(testUser.getId());

        mockMvc.perform(delete("/api/auth/webauthn/credentials/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("WEBAUTHN_CREDENTIAL_NOT_FOUND"));
    }

    @Test
    @DisplayName("刪除他人的生物辨識裝置應回傳 403")
    void should_Return403_When_DeleteCredential_BelongsToAnotherUser() throws Exception {
        User otherUser = userRepository.save(User.builder()
                .email("webauthn-other-" + UUID.randomUUID() + "@test.com")
                .passwordHash(passwordEncoder.encode("password123"))
                .fullName("其他使用者")
                .role("OWNER")
                .build());
        WebAuthnCredential credential = webAuthnCredentialRepository.save(WebAuthnCredential.builder()
                .userId(otherUser.getId())
                .credentialId("other-user-credential-" + UUID.randomUUID())
                .publicKeyCose("ZmFrZS1jb3NlLWtleQ")
                .signCount(0)
                .build());

        TokenContext.setUserId(testUser.getId());

        mockMvc.perform(delete("/api/auth/webauthn/credentials/" + credential.getId()))
                .andExpect(status().isForbidden());

        assertTrue(webAuthnCredentialRepository.findById(credential.getId()).isPresent());
    }

    @Test
    @DisplayName("刪除自己的生物辨識裝置應成功並從資料庫移除")
    void should_DeleteCredential_Successfully_When_OwnedByCaller() throws Exception {
        WebAuthnCredential credential = webAuthnCredentialRepository.save(WebAuthnCredential.builder()
                .userId(testUser.getId())
                .credentialId("delete-test-credential-" + UUID.randomUUID())
                .publicKeyCose("ZmFrZS1jb3NlLWtleQ")
                .signCount(0)
                .build());

        TokenContext.setUserId(testUser.getId());

        mockMvc.perform(delete("/api/auth/webauthn/credentials/" + credential.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        assertTrue(webAuthnCredentialRepository.findById(credential.getId()).isEmpty());
    }
}
