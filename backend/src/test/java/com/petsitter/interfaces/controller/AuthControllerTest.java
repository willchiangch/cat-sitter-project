package com.petsitter.interfaces.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petsitter.application.dto.DeactivateAccountRequest;
import com.petsitter.application.dto.GoogleLoginRequest;
import com.petsitter.application.dto.LoginRequest;
import com.petsitter.application.dto.RegisterRequest;
import com.petsitter.application.dto.ResendOtpRequest;
import com.petsitter.application.dto.VerifyRegistrationOtpRequest;
import com.petsitter.application.exception.GoogleAuthException;
import com.petsitter.application.service.EmailService;
import com.petsitter.application.service.GoogleTokenVerifierService;
import com.petsitter.application.service.GoogleUserInfo;
import com.petsitter.domain.model.FavoriteSitter;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.OrderItem;
import com.petsitter.domain.model.RegistrationOtp;
import com.petsitter.domain.model.TrustRelationship;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.FavoriteSitterRepository;
import com.petsitter.domain.repository.OrderRepository;
import com.petsitter.domain.repository.RegistrationOtpRepository;
import com.petsitter.domain.repository.TrustRelationshipRepository;
import com.petsitter.domain.repository.UserRepository;
import com.petsitter.domain.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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

    @Autowired
    private RegistrationOtpRepository registrationOtpRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TrustRelationshipRepository trustRelationshipRepository;

    @Autowired
    private FavoriteSitterRepository favoriteSitterRepository;

    @Autowired
    private com.petsitter.domain.repository.RefreshTokenRepository refreshTokenRepository;

    @MockitoBean
    private EmailService emailService;

    @MockitoBean
    private GoogleTokenVerifierService googleTokenVerifierService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        trustRelationshipRepository.deleteAll();
        favoriteSitterRepository.deleteAll();
        subscriptionRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        registrationOtpRepository.deleteAll();
        userRepository.deleteAll();
    }

    private Order createOrder(User owner, User sitter, String status) {
        OrderItem item = new OrderItem();
        item.setDates(List.of("2026-05-25", "2026-05-27"));
        Order order = Order.builder()
                .owner(owner)
                .sitter(sitter)
                .status(status)
                .planId(UUID.randomUUID())
                .items(new ArrayList<>(List.of(item)))
                .totalAmount(2400)
                .build();
        return orderRepository.save(order);
    }

    // --- 測試輔助方法 ---

    private RegisterRequest buildRegisterRequest(String email, String password, String fullName, String role) {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(email);
        request.setPassword(password);
        request.setFullName(fullName);
        request.setRole(role);
        return request;
    }

    /**
     * 從 Mock 的 EmailService 攔截寄出的驗證信 HTML，擷取本次（或最新一次重寄）的 6 碼 OTP。
     */
    private String captureLatestOtp(String email) {
        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendEmail(eq(email), anyString(), htmlCaptor.capture());
        Matcher matcher = Pattern.compile("(\\d{6})").matcher(htmlCaptor.getValue());
        if (!matcher.find()) {
            throw new IllegalStateException("測試無法從郵件內容擷取 OTP: " + htmlCaptor.getValue());
        }
        return matcher.group(1);
    }

    /**
     * 完整走一次「送出註冊表單 -> 取得 OTP -> 驗證」流程，回傳最終 AuthResponse JSON，
     * 供其餘既有測試（登入、忘記密碼、換發 Token）沿用已驗證帳號。
     */
    private String registerAndVerify(String email, String password, String fullName, String role) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(buildRegisterRequest(email, password, fullName, role))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OTP_SENT"));

        String otpCode = captureLatestOtp(email);

        VerifyRegistrationOtpRequest verifyRequest = new VerifyRegistrationOtpRequest();
        verifyRequest.setEmail(email);
        verifyRequest.setOtpCode(otpCode);

        return mockMvc.perform(post("/api/auth/register/verify-otp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(verifyRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andReturn().getResponse().getContentAsString();
    }

    // --- PRD-000 Email OTP 註冊驗證 ---

    @Test
    @DisplayName("送出註冊表單應回傳 OTP_SENT 且尚未建立使用者帳號")
    void should_Register_ReturnsOtpSent_And_NotCreateUserYet() throws Exception {
        String email = "pending@test.com";

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(buildRegisterRequest(email, "password123", "Pending User", "OWNER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OTP_SENT"))
                .andExpect(jsonPath("$.email").value(email));

        assertFalse(userRepository.existsByEmail(email));
        assertTrue(registrationOtpRepository.findByEmail(email).isPresent());
    }

    @Test
    @DisplayName("OTP 驗證成功應建立使用者並取得 Token (PRD-000 AC-1)")
    void should_VerifyOtp_Successfully_And_CreateUser_And_ReturnToken() throws Exception {
        String email = "verify@test.com";

        registerAndVerify(email, "password123", "Verify User", "OWNER");

        assertTrue(userRepository.existsByEmail(email));
        assertTrue(registrationOtpRepository.findByEmail(email).isEmpty());
    }

    @Test
    @DisplayName("OTP 輸入錯誤應回傳 400 OTP_INVALID 且累計錯誤次數")
    void should_Return400_When_OtpIncorrect_And_IncrementAttempts() throws Exception {
        String email = "otpwrong@test.com";
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(buildRegisterRequest(email, "password123", "Otp Wrong User", "OWNER"))))
                .andExpect(status().isOk());

        String correctOtp = captureLatestOtp(email);
        String wrongOtp = "000000".equals(correctOtp) ? "111111" : "000000";

        VerifyRegistrationOtpRequest wrongRequest = new VerifyRegistrationOtpRequest();
        wrongRequest.setEmail(email);
        wrongRequest.setOtpCode(wrongOtp);

        mockMvc.perform(post("/api/auth/register/verify-otp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(wrongRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("OTP_INVALID"));

        RegistrationOtp otp = registrationOtpRepository.findByEmail(email).orElseThrow();
        assertEquals(1, otp.getAttempts());
    }

    @Test
    @DisplayName("連續輸入錯誤驗證碼達上限應回傳 429 OTP_LOCKED")
    void should_Return429_When_OtpAttempts_ExceedLimit() throws Exception {
        String email = "otplock@test.com";
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(buildRegisterRequest(email, "password123", "Otp Lock User", "OWNER"))))
                .andExpect(status().isOk());

        String correctOtp = captureLatestOtp(email);
        String wrongOtp = "000000".equals(correctOtp) ? "111111" : "000000";

        VerifyRegistrationOtpRequest wrongRequest = new VerifyRegistrationOtpRequest();
        wrongRequest.setEmail(email);
        wrongRequest.setOtpCode(wrongOtp);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/register/verify-otp")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(wrongRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("OTP_INVALID"));
        }

        mockMvc.perform(post("/api/auth/register/verify-otp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(wrongRequest)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("OTP_LOCKED"));
    }

    @Test
    @DisplayName("驗證碼已過期時應回傳 400 OTP_EXPIRED")
    void should_Return400_When_OtpExpired() throws Exception {
        String email = "otpexpired@test.com";
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(buildRegisterRequest(email, "password123", "Otp Expired User", "OWNER"))))
                .andExpect(status().isOk());

        RegistrationOtp otp = registrationOtpRepository.findByEmail(email).orElseThrow();
        otp.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        registrationOtpRepository.save(otp);

        VerifyRegistrationOtpRequest verifyRequest = new VerifyRegistrationOtpRequest();
        verifyRequest.setEmail(email);
        verifyRequest.setOtpCode("123456");

        mockMvc.perform(post("/api/auth/register/verify-otp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(verifyRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("OTP_EXPIRED"));
    }

    @Test
    @DisplayName("重寄冷卻時間內重寄應回傳 429 OTP_RESEND_TOO_SOON")
    void should_Return429_When_ResendTooSoon() throws Exception {
        String email = "otpresend@test.com";
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(buildRegisterRequest(email, "password123", "Otp Resend User", "OWNER"))))
                .andExpect(status().isOk());

        ResendOtpRequest resendRequest = new ResendOtpRequest();
        resendRequest.setEmail(email);

        mockMvc.perform(post("/api/auth/register/resend-otp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(resendRequest)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("OTP_RESEND_TOO_SOON"));
    }

    @Test
    @DisplayName("Email 已是正式帳號時再次註冊應回傳 400")
    void should_Return400_When_Register_With_ExistingEmail() throws Exception {
        String email = "dup@test.com";
        registerAndVerify(email, "password123", "Dup User", "OWNER");

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(buildRegisterRequest(email, "password456", "Dup User 2", "OWNER"))))
                .andExpect(status().isBadRequest());
    }

    // --- 既有登入 / 忘記密碼 / 換發 Token 流程（改用 registerAndVerify 取得已驗證帳號）---

    @Test
    @DisplayName("登入成功應回傳 Token")
    void should_Login_Successfully() throws Exception {
        registerAndVerify("loginuser@test.com", "password123", "Login User", "SITTER");

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
        registerAndVerify("failuser@test.com", "password123", "Fail User", "OWNER");

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
        registerAndVerify("lockout@test.com", "password123", "Lockout User", "OWNER");

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
        registerAndVerify("reset@test.com", "oldpassword123", "Reset User", "OWNER");

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
        String responseJson = registerAndVerify("refresh@test.com", "password123", "Refresh User", "OWNER");

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

    // --- PRD-000 AC-8 帳號註銷（軟刪除）---

    @Test
    @DisplayName("密碼正確且無未結案訂單時應能成功註銷帳號 (PRD-000 AC-8)")
    void should_DeactivateAccount_Successfully() throws Exception {
        String responseJson = registerAndVerify("deactivate@test.com", "password123", "Deactivate User", "OWNER");
        String accessToken = com.jayway.jsonpath.JsonPath.read(responseJson, "$.accessToken");
        String userId = com.jayway.jsonpath.JsonPath.read(responseJson, "$.userId");

        DeactivateAccountRequest request = new DeactivateAccountRequest();
        request.setPassword("password123");

        mockMvc.perform(post("/api/auth/deactivate")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        User user = userRepository.findById(UUID.fromString(userId)).orElseThrow();
        assertTrue(user.isDeleted());
    }

    @Test
    @DisplayName("密碼錯誤時註銷應回傳 403（避免撞上前端全域 401 refresh-token 重試）")
    void should_Return403_When_DeactivatePasswordIncorrect() throws Exception {
        String responseJson = registerAndVerify("deactivatewrongpw@test.com", "password123", "Wrong Pw User", "OWNER");
        String accessToken = com.jayway.jsonpath.JsonPath.read(responseJson, "$.accessToken");

        DeactivateAccountRequest request = new DeactivateAccountRequest();
        request.setPassword("wrongpassword");

        mockMvc.perform(post("/api/auth/deactivate")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("尚有未結案訂單時註銷應回傳 409 ACCOUNT_DEACTIVATION_BLOCKED")
    void should_Return409_When_DeactivateWithActiveOrder() throws Exception {
        String responseJson = registerAndVerify("deactivateblocked@test.com", "password123", "Blocked User", "OWNER");
        String accessToken = com.jayway.jsonpath.JsonPath.read(responseJson, "$.accessToken");
        String userId = com.jayway.jsonpath.JsonPath.read(responseJson, "$.userId");
        User owner = userRepository.findById(UUID.fromString(userId)).orElseThrow();

        User sitter = userRepository.save(User.builder()
                .email("counterpart-sitter-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash").role("SITTER").fullName("配對保母").build());
        createOrder(owner, sitter, "CONFIRMED");

        DeactivateAccountRequest request = new DeactivateAccountRequest();
        request.setPassword("password123");

        mockMvc.perform(post("/api/auth/deactivate")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ACCOUNT_DEACTIVATION_BLOCKED"));
    }

    @Test
    @DisplayName("僅有已結案訂單 (COMPLETED/CANCELLED) 時不應阻擋註銷")
    void should_AllowDeactivate_When_OnlyTerminalOrdersExist() throws Exception {
        String responseJson = registerAndVerify("deactivateterminal@test.com", "password123", "Terminal User", "OWNER");
        String accessToken = com.jayway.jsonpath.JsonPath.read(responseJson, "$.accessToken");
        String userId = com.jayway.jsonpath.JsonPath.read(responseJson, "$.userId");
        User owner = userRepository.findById(UUID.fromString(userId)).orElseThrow();

        User sitter = userRepository.save(User.builder()
                .email("counterpart-sitter2-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash").role("SITTER").fullName("配對保母2").build());
        createOrder(owner, sitter, "COMPLETED");
        createOrder(owner, sitter, "CANCELLED");

        DeactivateAccountRequest request = new DeactivateAccountRequest();
        request.setPassword("password123");

        mockMvc.perform(post("/api/auth/deactivate")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("註銷後應無法再用原密碼登入")
    void should_RejectLogin_After_Deactivate() throws Exception {
        String responseJson = registerAndVerify("deactivatelogin@test.com", "password123", "Login Blocked User", "OWNER");
        String accessToken = com.jayway.jsonpath.JsonPath.read(responseJson, "$.accessToken");

        DeactivateAccountRequest request = new DeactivateAccountRequest();
        request.setPassword("password123");
        mockMvc.perform(post("/api/auth/deactivate")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail("deactivatelogin@test.com");
        loginRequest.setPassword("password123");

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("註銷後既有 Refresh Token 應失效")
    void should_RevokeRefreshToken_After_Deactivate() throws Exception {
        String responseJson = registerAndVerify("deactivaterefresh@test.com", "password123", "Refresh Blocked User", "OWNER");
        String accessToken = com.jayway.jsonpath.JsonPath.read(responseJson, "$.accessToken");
        String refreshToken = com.jayway.jsonpath.JsonPath.read(responseJson, "$.refreshToken");

        DeactivateAccountRequest request = new DeactivateAccountRequest();
        request.setPassword("password123");
        mockMvc.perform(post("/api/auth/deactivate")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        assertTrue(refreshTokenRepository.findByToken(refreshToken).isEmpty());
    }

    @Test
    @DisplayName("註銷後應自動清除本人涉及的信任圈與我的最愛關聯")
    void should_CleanupTrustCircleAndFavorites_After_Deactivate() throws Exception {
        String responseJson = registerAndVerify("deactivatesitter@test.com", "password123", "Deactivate Sitter", "SITTER");
        String accessToken = com.jayway.jsonpath.JsonPath.read(responseJson, "$.accessToken");
        String userId = com.jayway.jsonpath.JsonPath.read(responseJson, "$.userId");
        User sitter = userRepository.findById(UUID.fromString(userId)).orElseThrow();

        User otherSitter = userRepository.save(User.builder()
                .email("trust-partner-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash").role("SITTER").fullName("信任圈夥伴").build());
        User favoritingOwner = userRepository.save(User.builder()
                .email("favoriting-owner-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash").role("OWNER").fullName("收藏此保母的飼主").build());

        trustRelationshipRepository.save(TrustRelationship.builder()
                .requester(sitter).target(otherSitter).status("ACCEPTED").build());
        favoriteSitterRepository.save(FavoriteSitter.builder()
                .owner(favoritingOwner).sitter(sitter).build());

        DeactivateAccountRequest request = new DeactivateAccountRequest();
        request.setPassword("password123");
        mockMvc.perform(post("/api/auth/deactivate")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        assertTrue(trustRelationshipRepository.findAcceptedBySitterId(sitter.getId()).isEmpty());
        assertTrue(favoriteSitterRepository.findByOwnerIdAndSitterIdAndIsDeletedFalse(favoritingOwner.getId(), sitter.getId()).isEmpty());
    }

    // --- PRD-000 登出所有裝置 ---

    @Test
    @DisplayName("登出所有裝置後，該使用者的 refresh token 應被撤銷")
    void should_RevokeRefreshToken_After_LogoutAllDevices() throws Exception {
        String responseJson = registerAndVerify("logoutall@test.com", "password123", "Logout All User", "OWNER");
        String accessToken = com.jayway.jsonpath.JsonPath.read(responseJson, "$.accessToken");
        String refreshToken = com.jayway.jsonpath.JsonPath.read(responseJson, "$.refreshToken");

        mockMvc.perform(post("/api/auth/logout-all-devices")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        assertTrue(refreshTokenRepository.findByToken(refreshToken).isEmpty());
    }

    @Test
    @DisplayName("登出所有裝置後，帳號本身仍可用原密碼重新登入")
    void should_StillAllowLogin_After_LogoutAllDevices() throws Exception {
        String responseJson = registerAndVerify("logoutallrelogin@test.com", "password123", "Relogin User", "OWNER");
        String accessToken = com.jayway.jsonpath.JsonPath.read(responseJson, "$.accessToken");

        mockMvc.perform(post("/api/auth/logout-all-devices")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail("logoutallrelogin@test.com");
        loginRequest.setPassword("password123");
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());
    }

    // --- PRD-000 AC-5 Google 第三方登入 ---

    @Test
    @DisplayName("Google 登入新 Email 且未帶角色時應回傳 NEEDS_ROLE_SELECTION 且不建立帳號")
    void should_ReturnNeedsRoleSelection_When_GoogleLogin_NewEmail_NoRole() throws Exception {
        when(googleTokenVerifierService.verify("fake-google-token"))
                .thenReturn(new GoogleUserInfo("googlenew@test.com", "Google New User", true));

        GoogleLoginRequest request = new GoogleLoginRequest();
        request.setIdToken("fake-google-token");

        mockMvc.perform(post("/api/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_ROLE_SELECTION"))
                .andExpect(jsonPath("$.email").value("googlenew@test.com"));

        assertFalse(userRepository.existsByEmail("googlenew@test.com"));
    }

    @Test
    @DisplayName("Google 登入新 Email 且帶入角色時應建立帳號並核發 Token")
    void should_CreateUser_When_GoogleLogin_NewEmail_WithRole() throws Exception {
        when(googleTokenVerifierService.verify("fake-google-token-2"))
                .thenReturn(new GoogleUserInfo("googlenewrole@test.com", "Google Role User", true));

        GoogleLoginRequest request = new GoogleLoginRequest();
        request.setIdToken("fake-google-token-2");
        request.setRole("OWNER");

        mockMvc.perform(post("/api/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.accessToken").exists());

        User created = userRepository.findByEmail("googlenewrole@test.com").orElseThrow();
        assertEquals("OWNER", created.getRole());
    }

    @Test
    @DisplayName("Google 登入 Email 已是既有帳號時應直接自動綁定登入")
    void should_AutoLogin_When_GoogleLogin_ExistingEmail() throws Exception {
        String existingResponseJson = registerAndVerify("googlebound@test.com", "password123", "Existing User", "OWNER");
        String existingUserId = com.jayway.jsonpath.JsonPath.read(existingResponseJson, "$.userId");

        when(googleTokenVerifierService.verify("fake-google-token-3"))
                .thenReturn(new GoogleUserInfo("googlebound@test.com", "Existing User", true));

        GoogleLoginRequest request = new GoogleLoginRequest();
        request.setIdToken("fake-google-token-3");

        String responseJson = mockMvc.perform(post("/api/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.accessToken").exists())
                .andReturn().getResponse().getContentAsString();

        String returnedUserId = com.jayway.jsonpath.JsonPath.read(responseJson, "$.userId");
        assertEquals(existingUserId, returnedUserId);
    }

    @Test
    @DisplayName("Google 登入對應已註銷帳號時應回傳 401")
    void should_Return401_When_GoogleLogin_ExistingButDeletedEmail() throws Exception {
        registerAndVerify("googledeleted@test.com", "password123", "Deleted User", "OWNER");
        User user = userRepository.findByEmail("googledeleted@test.com").orElseThrow();
        user.setDeleted(true);
        userRepository.save(user);

        when(googleTokenVerifierService.verify("fake-google-token-4"))
                .thenReturn(new GoogleUserInfo("googledeleted@test.com", "Deleted User", true));

        GoogleLoginRequest request = new GoogleLoginRequest();
        request.setIdToken("fake-google-token-4");

        mockMvc.perform(post("/api/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Google Token 驗證失敗時應回傳 401 GOOGLE_TOKEN_INVALID")
    void should_Return401_When_GoogleTokenInvalid() throws Exception {
        when(googleTokenVerifierService.verify("invalid-token"))
                .thenThrow(new GoogleAuthException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED, "GOOGLE_TOKEN_INVALID", "Google 登入驗證失敗"));

        GoogleLoginRequest request = new GoogleLoginRequest();
        request.setIdToken("invalid-token");

        mockMvc.perform(post("/api/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("GOOGLE_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("Google 帳號 Email 未驗證時應回傳 401 GOOGLE_EMAIL_NOT_VERIFIED")
    void should_Return401_When_GoogleEmailNotVerified() throws Exception {
        when(googleTokenVerifierService.verify("unverified-email-token"))
                .thenReturn(new GoogleUserInfo("unverified@test.com", "Unverified User", false));

        GoogleLoginRequest request = new GoogleLoginRequest();
        request.setIdToken("unverified-email-token");

        mockMvc.perform(post("/api/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("GOOGLE_EMAIL_NOT_VERIFIED"));
    }
}
