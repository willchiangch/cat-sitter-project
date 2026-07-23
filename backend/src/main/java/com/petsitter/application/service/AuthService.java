package com.petsitter.application.service;

import com.petsitter.application.dto.AuthResponse;
import com.petsitter.application.dto.DeactivateAccountRequest;
import com.petsitter.application.dto.ForgotPasswordRequest;
import com.petsitter.application.dto.GoogleLoginRequest;
import com.petsitter.application.dto.GoogleLoginResponse;
import com.petsitter.application.dto.LoginRequest;
import com.petsitter.application.dto.RegisterRequest;
import com.petsitter.application.dto.RegisterResponse;
import com.petsitter.application.dto.ResendOtpRequest;
import com.petsitter.application.dto.ResetPasswordRequest;
import com.petsitter.application.dto.TokenRefreshRequest;
import com.petsitter.application.dto.VerifyRegistrationOtpRequest;
import com.petsitter.application.exception.AccountDeactivationException;
import com.petsitter.application.exception.GoogleAuthException;
import com.petsitter.application.exception.RegistrationException;
import com.petsitter.domain.model.PasswordResetToken;
import com.petsitter.domain.model.RefreshToken;
import com.petsitter.domain.model.RegistrationOtp;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.FavoriteSitterRepository;
import com.petsitter.domain.repository.OrderRepository;
import com.petsitter.domain.repository.PasswordResetTokenRepository;
import com.petsitter.domain.repository.RefreshTokenRepository;
import com.petsitter.domain.repository.RegistrationOtpRepository;
import com.petsitter.domain.repository.TrustRelationshipRepository;
import com.petsitter.domain.repository.UserRepository;
import com.petsitter.infrastructure.security.JwtUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final AuthenticationManager authenticationManager;
    private final com.petsitter.domain.repository.ProfileRepository profileRepository;
    private final LoginAttemptService loginAttemptService;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RegistrationOtpRepository registrationOtpRepository;
    private final RegistrationOtpAttemptService registrationOtpAttemptService;
    private final OrderRepository orderRepository;
    private final TrustRelationshipRepository trustRelationshipRepository;
    private final FavoriteSitterRepository favoriteSitterRepository;
    private final GoogleTokenVerifierService googleTokenVerifierService;
    private final EmailService emailService;
    private final AuditLogService auditLogService;

    @Value("${app.frontend-base-url}")
    private String frontendBaseUrl;

    private static final long ACCESS_TOKEN_EXPIRY = 15 * 60 * 1000; // 15 mins
    private static final long REFRESH_TOKEN_EXPIRY = 7 * 24 * 60 * 60 * 1000; // 7 days
    private static final long PASSWORD_RESET_TOKEN_EXPIRY_MINUTES = 30;
    private static final long OTP_EXPIRY_MINUTES = 10;
    private static final long OTP_RESEND_COOLDOWN_SECONDS = 60;
    private static final int OTP_MAX_ATTEMPTS = 5;
    private static final SecureRandom OTP_RANDOM = new SecureRandom();

    /**
     * PRD-000 AC-1：註冊不再直接建立帳號，改為寄送 Email OTP，待 {@link #verifyRegistrationOtp} 驗證通過才正式建立 User。
     */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("電子郵件已存在");
        }

        String otpCode = generateOtpCode();
        registrationOtpRepository.findByEmail(request.getEmail())
                .ifPresent(registrationOtpRepository::delete);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        RegistrationOtp registrationOtp = RegistrationOtp.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .role(request.getRole())
                .otpHash(passwordEncoder.encode(otpCode))
                .attempts(0)
                .expiresAt(now.plusMinutes(OTP_EXPIRY_MINUTES))
                .lastSentAt(now)
                .build();
        registrationOtpRepository.saveAndFlush(registrationOtp);

        sendOtpEmail(request.getEmail(), request.getFullName(), otpCode);

        return RegisterResponse.builder()
                .status("OTP_SENT")
                .email(request.getEmail())
                .build();
    }

    @Transactional
    public AuthResponse verifyRegistrationOtp(VerifyRegistrationOtpRequest request) {
        RegistrationOtp registrationOtp = registrationOtpRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RegistrationException(HttpStatus.BAD_REQUEST, "OTP_EXPIRED", "驗證碼已過期，請重新註冊"));

        if (registrationOtp.getExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new RegistrationException(HttpStatus.BAD_REQUEST, "OTP_EXPIRED", "驗證碼已過期，請重新註冊");
        }
        if (registrationOtp.getAttempts() >= OTP_MAX_ATTEMPTS) {
            throw new RegistrationException(HttpStatus.TOO_MANY_REQUESTS, "OTP_LOCKED", "驗證失敗次數過多，請重新寄送驗證碼");
        }
        if (!passwordEncoder.matches(request.getOtpCode(), registrationOtp.getOtpHash())) {
            registrationOtpAttemptService.registerFailedOtpAttempt(registrationOtp.getId());
            throw new RegistrationException(HttpStatus.BAD_REQUEST, "OTP_INVALID", "驗證碼錯誤");
        }

        if (userRepository.existsByEmail(registrationOtp.getEmail())) {
            registrationOtpRepository.delete(registrationOtp);
            throw new IllegalArgumentException("電子郵件已存在");
        }

        User user = User.builder()
                .email(registrationOtp.getEmail())
                .passwordHash(registrationOtp.getPasswordHash())
                .fullName(registrationOtp.getFullName())
                .role(registrationOtp.getRole())
                .build();
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // 併發防禦：同一 Email 兩個分頁同時完成驗證時，DB UNIQUE 約束兜底，回友善錯誤而非未映射 500
            throw new IllegalArgumentException("電子郵件已存在");
        }
        registrationOtpRepository.delete(registrationOtp);
        auditLogService.writeUserActionLogInline("AUTH_REGISTER", "CREATE", user.getId(), user.getId(), "users");

        return createAuthResponse(user);
    }

    /**
     * 不洩漏帳號存在性：查無待驗證紀錄時仍回相同流程，僅實際跳過寄信（比照 {@link #forgotPassword}）。
     */
    @Transactional
    public void resendRegistrationOtp(ResendOtpRequest request) {
        registrationOtpRepository.findByEmail(request.getEmail()).ifPresent(registrationOtp -> {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            if (registrationOtp.getLastSentAt().plusSeconds(OTP_RESEND_COOLDOWN_SECONDS).isAfter(now)) {
                throw new RegistrationException(HttpStatus.TOO_MANY_REQUESTS, "OTP_RESEND_TOO_SOON", "請稍後再重新寄送");
            }

            String otpCode = generateOtpCode();
            registrationOtp.setOtpHash(passwordEncoder.encode(otpCode));
            registrationOtp.setAttempts(0);
            registrationOtp.setExpiresAt(now.plusMinutes(OTP_EXPIRY_MINUTES));
            registrationOtp.setLastSentAt(now);
            registrationOtpRepository.save(registrationOtp);

            sendOtpEmail(registrationOtp.getEmail(), registrationOtp.getFullName(), otpCode);
        });
    }

    private String generateOtpCode() {
        return String.format("%06d", OTP_RANDOM.nextInt(1_000_000));
    }

    private void sendOtpEmail(String email, String fullName, String otpCode) {
        emailService.sendEmail(
                email,
                "WhiskerWatch 註冊驗證碼",
                "<p>您好 " + fullName + "，</p>"
                        + "<p>您的註冊驗證碼為：</p>"
                        + "<p style=\"font-size: 24px; font-weight: bold;\">" + otpCode + "</p>"
                        + "<p>驗證碼將於 " + OTP_EXPIRY_MINUTES + " 分鐘後失效，請勿將驗證碼提供給他人。</p>"
                        + "<p>若您並未申請註冊，請忽略此信件。</p>"
        );
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BadCredentialsException("帳號或密碼錯誤"));

        // PRD-000 AC-8：已註銷帳號一律拒絕登入，不透露帳號已註銷（統一走密碼錯誤訊息避免帳號枚舉）
        if (user.isDeleted()) {
            throw new BadCredentialsException("帳號或密碼錯誤");
        }

        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new LockedException("嘗試次數過多，請於 10 分鐘後再試");
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
        } catch (BadCredentialsException e) {
            loginAttemptService.registerFailedLoginAttempt(user.getId());
            throw e;
        }

        if (user.getFailedLoginAttempts() > 0 || user.getLockedUntil() != null) {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            userRepository.save(user);
        }

        return createAuthResponse(user);
    }


    @Transactional
    public AuthResponse refreshToken(TokenRefreshRequest request) {
        String requestRefreshToken = request.getRefreshToken();

        return refreshTokenRepository.findByToken(requestRefreshToken)
                .map(this::verifyExpiration)
                .map(token -> {
                    User user = token.getUser();
                    if (token.getActiveRole() == null) {
                        log.warn("[AuthService] Refresh token has no activeRole, falling back to user default role: {} for email: {}", user.getRole(), user.getEmail());
                    }
                    // Null-safety fallback: 若 activeRole 欄位為 NULL，則 fallback 回退至 user.getRole()
                    String role = token.getActiveRole() != null ? token.getActiveRole() : user.getRole();
                    
                    java.util.Map<String, Object> extraClaims = new java.util.HashMap<>();
                    extraClaims.put("userId", user.getId().toString());
                    String accessToken = jwtUtils.generateToken(user.getEmail(), role, extraClaims, ACCESS_TOKEN_EXPIRY);
                    
                    return AuthResponse.builder()
                            .accessToken(accessToken)
                            .refreshToken(requestRefreshToken)
                            .userId(user.getId())
                            .email(user.getEmail())
                            .fullName(user.getFullName())
                            .role(role)
                            .build();
                })
                .orElseThrow(() -> new RuntimeException("Refresh token 找不到或無效"));
    }

    /**
     * 敏感操作二次驗證：核對指定使用者的密碼是否正確 (SD-009 NFR-003)
     */
    public boolean verifyPassword(String email, String rawPassword) {
        return userRepository.findByEmail(email)
                .map(user -> passwordEncoder.matches(rawPassword, user.getPasswordHash()))
                .orElse(false);
    }

    @Transactional
    public AuthResponse switchRole(User user, String targetRole) {
        log.info("[AuthService] Switching role for user: {} to targetRole: {}", user.getEmail(), targetRole);
        if (!"CLIENT".equals(targetRole) && !"SITTER".equals(targetRole)) {
            log.warn("[AuthService] Invalid targetRole: {} for user: {}", targetRole, user.getEmail());
            throw new IllegalArgumentException("無效的目標角色");
        }

        // Lazy initialization of Profile
        try {
            if (profileRepository.findByUserIdAndType(user.getId(), targetRole).isEmpty()) {
                log.info("[AuthService] Lazy initializing profile type: {} for user: {}", targetRole, user.getId());
                com.petsitter.domain.model.Profile profile = com.petsitter.domain.model.Profile.builder()
                        .userId(user.getId())
                        .type(targetRole)
                        .trustScore(100)
                        .kycStatus("UNVERIFIED")
                        .build();
                profileRepository.saveAndFlush(profile);
            }
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 併發衝突防禦：若已被另一執行緒建立，重新查詢即可，不拋錯
            log.info("[AuthService] Profile initialization race conflict resolved dynamically for user: {} role: {}", user.getId(), targetRole);
            profileRepository.findByUserIdAndType(user.getId(), targetRole)
                    .orElseThrow(() -> e);
        }

        // 1. 註銷舊的 refresh token
        refreshTokenRepository.deleteByUser(user);
        
        // 2. 建立新的 refresh token，並將 activeRole 寫入為 targetRole
        RefreshToken newRefreshToken = RefreshToken.builder()
                .user(user)
                .token(UUID.randomUUID().toString())
                .expiryDate(Instant.now().plusMillis(REFRESH_TOKEN_EXPIRY))
                .activeRole(targetRole)
                .build();
        refreshTokenRepository.save(newRefreshToken);
        
        // 3. 簽發新的 access token (claims 塞入 userId 與 targetRole)
        java.util.Map<String, Object> extraClaims = new java.util.HashMap<>();
        extraClaims.put("userId", user.getId().toString());
        String accessToken = jwtUtils.generateToken(user.getEmail(), targetRole, extraClaims, ACCESS_TOKEN_EXPIRY);
        
        log.info("[AuthService] Switched role successfully for user: {} to role: {}", user.getEmail(), targetRole);
        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(newRefreshToken.getToken())
                .userId(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(targetRole)
                .build();
    }

    /**
     * PRD-000「登出所有裝置」。現況架構下同一帳號同時只保留一組 refresh token
     * （見 {@link #createRefreshToken}／{@link #switchRole}），因此本操作等同撤銷目前僅有的那組 session；
     * 尚未支援真正多裝置並存架構，屬已知限制。
     */
    @Transactional
    public void logoutAllDevices(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("找不到使用者資訊"));
        refreshTokenRepository.deleteByUser(user);
        auditLogService.writeUserActionLog("AUTH_LOGOUT_ALL", "DELETE", userId, userId, "refresh_tokens");
    }

    /**
     * PRD-000 AC-8：帳號註銷（軟刪除）。前置檢查名下（飼主或保母角色）是否仍有未結案訂單，
     * 通過後標記 users.is_deleted、撤銷所有裝置登入，並自動清除信任圈與我的最愛關聯（非阻擋條件）。
     */
    @Transactional
    public void deactivateAccount(UUID userId, String password) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("找不到使用者資訊"));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            // 故意用 403 而非 401，理由同 SD-009 管理員二次驗證：避免撞上前端 axios 對所有 401 的
            // 全域 refresh-token 靜默重試機制，把「密碼驗證失敗」誤判成 session 過期
            throw new AccessDeniedException("密碼驗證失敗，無法執行帳號註銷");
        }
        if (orderRepository.existsActiveOrderForParty(userId)) {
            throw new AccountDeactivationException(HttpStatus.CONFLICT, "ACCOUNT_DEACTIVATION_BLOCKED",
                    "您尚有未結案的訂單，請先完成或取消後再嘗試註銷帳號");
        }

        user.setDeleted(true);
        userRepository.save(user);
        refreshTokenRepository.deleteByUser(user);
        trustRelationshipRepository.softDeleteByPartyId(userId);
        favoriteSitterRepository.softDeleteByPartyId(userId);
        auditLogService.writeUserActionLog("AUTH_DEACTIVATE", "DELETE", userId, userId, "users");
    }

    /**
     * PRD-000 AC-5：Google 第三方登入。既有 Email 直接自動綁定登入；
     * 全新 Email 首次呼叫（不帶 role）回傳 NEEDS_ROLE_SELECTION，待前端帶入選定角色後才正式建立帳號。
     */
    @Transactional
    public GoogleLoginResponse loginWithGoogle(GoogleLoginRequest request) {
        GoogleUserInfo info = googleTokenVerifierService.verify(request.getIdToken());
        if (!info.emailVerified()) {
            throw new GoogleAuthException(HttpStatus.UNAUTHORIZED, "GOOGLE_EMAIL_NOT_VERIFIED", "Google 帳號 Email 尚未驗證");
        }

        Optional<User> existing = userRepository.findByEmail(info.email());
        if (existing.isPresent()) {
            User user = existing.get();
            if (user.isDeleted()) {
                throw new BadCredentialsException("帳號或密碼錯誤");
            }
            return toGoogleLoginResponse(createAuthResponse(user));
        }

        if (request.getRole() == null || request.getRole().isBlank()) {
            return GoogleLoginResponse.builder()
                    .status("NEEDS_ROLE_SELECTION")
                    .email(info.email())
                    .fullName(info.fullName())
                    .build();
        }
        if (!"OWNER".equals(request.getRole()) && !"SITTER".equals(request.getRole())) {
            throw new IllegalArgumentException("角色必須是 OWNER 或 SITTER");
        }

        User newUser = User.builder()
                .email(info.email())
                .passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
                .fullName(info.fullName())
                .role(request.getRole())
                .build();
        try {
            userRepository.saveAndFlush(newUser);
        } catch (DataIntegrityViolationException e) {
            // 併發防禦：同一 Email 兩個分頁同時完成角色選擇時，DB UNIQUE 約束兜底，回友善錯誤而非未映射 500
            throw new IllegalArgumentException("電子郵件已存在");
        }
        auditLogService.writeUserActionLogInline("AUTH_REGISTER", "CREATE", newUser.getId(), newUser.getId(), "users");
        return toGoogleLoginResponse(createAuthResponse(newUser));
    }

    private GoogleLoginResponse toGoogleLoginResponse(AuthResponse authResponse) {
        return GoogleLoginResponse.builder()
                .status("SUCCESS")
                .accessToken(authResponse.getAccessToken())
                .refreshToken(authResponse.getRefreshToken())
                .userId(authResponse.getUserId())
                .role(authResponse.getRole())
                .build();
    }

    /**
     * package-private：也供同套件的 {@link WebAuthnService} 在生物辨識登入成功後重用同一套 JWT 簽發邏輯。
     */
    AuthResponse createAuthResponse(User user) {
        java.util.Map<String, Object> extraClaims = new java.util.HashMap<>();
        extraClaims.put("userId", user.getId().toString());
        
        String accessToken = jwtUtils.generateToken(user.getEmail(), user.getRole(), extraClaims, ACCESS_TOKEN_EXPIRY);
        String refreshToken = createRefreshToken(user).getToken();

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .userId(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole())
                .build();
    }

    private RefreshToken createRefreshToken(User user) {
        // 移除舊的 refresh token (簡單起見，一個使用者一個)
        refreshTokenRepository.deleteByUser(user);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .token(UUID.randomUUID().toString())
                .expiryDate(Instant.now().plusMillis(REFRESH_TOKEN_EXPIRY))
                .activeRole(user.getRole()) // 預設為 user 初始角色
                .build();

        return refreshTokenRepository.save(refreshToken);
    }

    private RefreshToken verifyExpiration(RefreshToken token) {
        if (token.getExpiryDate().isBefore(Instant.now()) || token.isRevoked()) {
            refreshTokenRepository.delete(token);
            throw new RuntimeException("Refresh token 已過期或已被撤銷，請重新登入");
        }
        return token;
    }

    /**
     * PRD-000：忘記密碼。刻意無論帳號是否存在都回傳同樣的成功訊息，避免被拿來列舉已註冊 email。
     */
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        userRepository.findByEmail(request.getEmail()).ifPresent(user -> {
            PasswordResetToken resetToken = PasswordResetToken.builder()
                    .user(user)
                    .token(UUID.randomUUID().toString())
                    .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(PASSWORD_RESET_TOKEN_EXPIRY_MINUTES))
                    .build();
            passwordResetTokenRepository.save(resetToken);

            String resetLink = frontendBaseUrl + "/reset-password?token=" + resetToken.getToken();
            emailService.sendEmail(
                    user.getEmail(),
                    "WhiskerWatch 密碼重設",
                    "<p>您好 " + user.getFullName() + "，</p>"
                            + "<p>請點擊以下連結重設密碼，此連結將於 30 分鐘後失效：</p>"
                            + "<p><a href=\"" + resetLink + "\">" + resetLink + "</a></p>"
                            + "<p>若您並未申請重設密碼，請忽略此信件。</p>"
            );
        });
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        PasswordResetToken resetToken = passwordResetTokenRepository.findByToken(request.getToken())
                .orElseThrow(() -> new IllegalArgumentException("無效的重設連結"));

        if (resetToken.isUsed()) {
            throw new IllegalStateException("此重設連結已被使用過");
        }
        if (resetToken.getExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new IllegalStateException("此重設連結已過期，請重新申請");
        }

        User user = resetToken.getUser();
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        resetToken.setUsed(true);
        passwordResetTokenRepository.save(resetToken);

        refreshTokenRepository.deleteByUser(user);
    }
}
