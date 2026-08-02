package com.petsitter.application.service;

import com.petsitter.domain.model.User;
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
        log.info("[E2eJourneyAccountProvisioningService] Provisioned test account: {}", email);
        return user.getId();
    }
}
