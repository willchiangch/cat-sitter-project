package com.petsitter.application.service;

import com.petsitter.domain.model.User;
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registerFailedLoginAttempt(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow();
        int attempts = user.getFailedLoginAttempts() + 1;
        if (attempts >= MAX_FAILED_LOGIN_ATTEMPTS) {
            user.setLockedUntil(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(LOCKOUT_MINUTES));
            user.setFailedLoginAttempts(0); // 鎖定期間過後重新從 0 開始累計
        } else {
            user.setFailedLoginAttempts(attempts);
        }
        userRepository.save(user);
    }
}
