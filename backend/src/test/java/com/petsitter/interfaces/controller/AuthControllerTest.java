package com.petsitter.interfaces.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petsitter.application.dto.LoginRequest;
import com.petsitter.application.dto.RegisterRequest;
import com.petsitter.domain.repository.UserRepository;
import com.petsitter.domain.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("local")
@DisplayName("SD-000: 認證與授權整合測試")
class AuthControllerTest {

    static {
        System.setProperty("com.github.dockerjava.api.version", "1.44");
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private com.petsitter.domain.repository.PasswordResetTokenRepository passwordResetTokenRepository;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        subscriptionRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("應能成功註冊新使用者並取得 Token")
    void should_Register_Successfully() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("newuser@test.com");
        request.setPassword("password123");
        request.setFullName("New User");
        request.setRole("OWNER");

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.email").value("newuser@test.com"));
    }

    @Test
    @DisplayName("登入成功應回傳 Token")
    void should_Login_Successfully() throws Exception {
        // Given: 先註冊一個使用者
        RegisterRequest regRequest = new RegisterRequest();
        regRequest.setEmail("loginuser@test.com");
        regRequest.setPassword("password123");
        regRequest.setFullName("Login User");
        regRequest.setRole("SITTER");
        
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(regRequest)))
                .andExpect(status().isOk());

        // When: 嘗試登入
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail("loginuser@test.com");
        loginRequest.setPassword("password123");

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.role").value("SITTER"));
    }

    @Test
    @DisplayName("密碼錯誤應回傳 401")
    void should_Return401_When_PasswordIsIncorrect() throws Exception {
        // Given: 先註冊一個使用者
        RegisterRequest regRequest = new RegisterRequest();
        regRequest.setEmail("failuser@test.com");
        regRequest.setPassword("password123");
        regRequest.setFullName("Fail User");
        regRequest.setRole("OWNER");
        
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(regRequest)))
                .andExpect(status().isOk());

        // When: 嘗試用錯密碼登入
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail("failuser@test.com");
        loginRequest.setPassword("wrongpassword");

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("連續 5 次登入失敗應鎖定帳號 10 分鐘 (PRD-000 AC-4)")
    void should_LockAccount_After5FailedAttempts() throws Exception {
        RegisterRequest regRequest = new RegisterRequest();
        regRequest.setEmail("lockout@test.com");
        regRequest.setPassword("password123");
        regRequest.setFullName("Lockout User");
        regRequest.setRole("OWNER");

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(regRequest)))
                .andExpect(status().isOk());

        LoginRequest wrongLogin = new LoginRequest();
        wrongLogin.setEmail("lockout@test.com");
        wrongLogin.setPassword("wrongpassword");

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(wrongLogin)))
                    .andExpect(status().isUnauthorized());
        }

        // 第 6 次即使密碼正確，也應被鎖定拒絕
        LoginRequest correctLogin = new LoginRequest();
        correctLogin.setEmail("lockout@test.com");
        correctLogin.setPassword("password123");

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(correctLogin)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("ACCOUNT_LOCKED"));
    }

    @Test
    @DisplayName("忘記密碼流程：申請重設連結後可用該 token 設定新密碼並用新密碼登入")
    void should_ResetPassword_Successfully() throws Exception {
        RegisterRequest regRequest = new RegisterRequest();
        regRequest.setEmail("reset@test.com");
        regRequest.setPassword("oldpassword123");
        regRequest.setFullName("Reset User");
        regRequest.setRole("OWNER");

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(regRequest)))
                .andExpect(status().isOk());

        com.petsitter.application.dto.ForgotPasswordRequest forgotRequest =
                new com.petsitter.application.dto.ForgotPasswordRequest();
        forgotRequest.setEmail("reset@test.com");

        mockMvc.perform(post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(forgotRequest)))
                .andExpect(status().isOk());

        String token = passwordResetTokenRepository.findAll().get(0).getToken();

        com.petsitter.application.dto.ResetPasswordRequest resetRequest =
                new com.petsitter.application.dto.ResetPasswordRequest();
        resetRequest.setToken(token);
        resetRequest.setNewPassword("newpassword456");

        mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(resetRequest)))
                .andExpect(status().isOk());

        // 舊密碼應失效
        LoginRequest oldLogin = new LoginRequest();
        oldLogin.setEmail("reset@test.com");
        oldLogin.setPassword("oldpassword123");
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(oldLogin)))
                .andExpect(status().isUnauthorized());

        // 新密碼應可登入
        LoginRequest newLogin = new LoginRequest();
        newLogin.setEmail("reset@test.com");
        newLogin.setPassword("newpassword456");
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(newLogin)))
                .andExpect(status().isOk());

        // 同一個 token 不可重複使用
        mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(resetRequest)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("應能使用有效的 Refresh Token 換發新的 Access Token")
    void should_RefreshToken_Successfully() throws Exception {
        // Given: 先註冊並登入取得 Refresh Token
        RegisterRequest regRequest = new RegisterRequest();
        regRequest.setEmail("refresh@test.com");
        regRequest.setPassword("password123");
        regRequest.setFullName("Refresh User");
        regRequest.setRole("OWNER");
        
        String responseJson = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(regRequest)))
                .andReturn().getResponse().getContentAsString();
        
        String refreshToken = com.jayway.jsonpath.JsonPath.read(responseJson, "$.refreshToken");

        // When: 換發 Token
        String refreshRequest = "{\"refreshToken\":\"" + refreshToken + "\"}";
        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").value(refreshToken));
    }
}
