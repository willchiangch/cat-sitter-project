package com.petsitter.application.service;

import com.petsitter.domain.model.Profile;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.ProfileRepository;
import com.petsitter.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 供 frontend/e2e/journeys/ 真後端 journey 測試建立略過 Email OTP 驗證的帳號。
 * 僅允許 @e2e-journey.test 網域（RFC 2606 保留測試 TLD），即使呼叫方持有正確的
 * X-Internal-Secret 也一併擋下其餘 email，避免這支端點被誤用來建立任意帳號。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class E2eJourneyAccountProvisioningService {

    private static final Pattern ALLOWED_EMAIL = Pattern.compile("^journey-.*@e2e-journey\\.test$");

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public UUID provisionAccount(String email, String password, String fullName, String role) {
        if (!ALLOWED_EMAIL.matcher(email).matches()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "僅允許建立 @e2e-journey.test 測試帳號");
        }

        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .fullName(fullName)
                .role(role)
                .build();
        userRepository.saveAndFlush(user);

        // 比照 AuthService.verifyRegistrationOtp() 2026-09 的修復：建帳號當下就把對應的
        // Profile 一併建好，不然用這支端點建的測試帳號一樣會卡在「找不到該保母資料」，
        // 呼叫方還得額外記得手動打 /api/auth/switch-role 才能繞過去。
        String profileType = "OWNER".equals(role) ? "CLIENT" : "SITTER".equals(role) ? "SITTER" : null;
        if (profileType != null) {
            Profile profile = Profile.builder()
                    .userId(user.getId())
                    .type(profileType)
                    .trustScore(100)
                    .kycStatus("UNVERIFIED")
                    .build();
            profileRepository.saveAndFlush(profile);
        }

        log.info("[E2eJourneyAccountProvisioningService] Provisioned test account: {}", email);
        return user.getId();
    }
}
