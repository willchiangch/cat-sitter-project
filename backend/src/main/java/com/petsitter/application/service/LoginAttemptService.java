package com.petsitter.application.service;

import com.petsitter.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * PRD-000 AC-4：連續 5 次登入失敗鎖定帳號 10 分鐘。
 * 獨立成一支 Service 是因為 AuthService.login() 最終會重新拋出 BadCredentialsException，
 * 若失敗次數寫入跟 login() 共用同一筆交易，會隨著該例外一起被 rollback，鎖定機制永遠不會生效。
 */
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;
    private static final long LOCKOUT_MINUTES = 10;

    private final UserRepository userRepository;

    /**
     * 條件式 bulk UPDATE 原子完成「+1、達門檻設鎖定」，同帳號併發打錯密碼不會撞 users.version 409（backlog G3），
     * 見 {@link UserRepository#registerFailedLoginAttempt}。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registerFailedLoginAttempt(UUID userId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        userRepository.registerFailedLoginAttempt(userId, MAX_FAILED_LOGIN_ATTEMPTS, now,
                now.plusMinutes(LOCKOUT_MINUTES));
    }
}
