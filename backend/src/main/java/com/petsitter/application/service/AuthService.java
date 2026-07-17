package com.petsitter.application.service;

import com.petsitter.application.dto.AuthResponse;
import com.petsitter.application.dto.LoginRequest;
import com.petsitter.application.dto.RegisterRequest;
import com.petsitter.application.dto.TokenRefreshRequest;
import com.petsitter.domain.model.RefreshToken;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.RefreshTokenRepository;
import com.petsitter.domain.repository.UserRepository;
import com.petsitter.infrastructure.security.JwtUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
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

    private static final long ACCESS_TOKEN_EXPIRY = 15 * 60 * 1000; // 15 mins
    private static final long REFRESH_TOKEN_EXPIRY = 7 * 24 * 60 * 60 * 1000; // 7 days

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("電子郵件已存在");
        }

        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .role(request.getRole())
                .build();

        userRepository.save(user);
        return createAuthResponse(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BadCredentialsException("使用者不存在"));

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

    private AuthResponse createAuthResponse(User user) {
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
}
