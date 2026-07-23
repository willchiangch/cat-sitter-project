package com.petsitter.application.service;

import com.petsitter.domain.model.RegistrationOtp;
import com.petsitter.domain.repository.RegistrationOtpRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * PRD-000：Email OTP 註冊驗證錯誤次數累計。
 * 獨立成一支 Service 是因為 AuthService.verifyRegistrationOtp() 驗證失敗時最終會拋出 RegistrationException，
 * 若錯誤次數寫入跟該方法共用同一筆交易，會隨著該例外一起被 rollback，鎖定機制永遠不會生效
 * （比照 LoginAttemptService 的既有作法）。
 */
@Service
@RequiredArgsConstructor
public class RegistrationOtpAttemptService {

    private final RegistrationOtpRepository registrationOtpRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registerFailedOtpAttempt(UUID registrationOtpId) {
        RegistrationOtp registrationOtp = registrationOtpRepository.findById(registrationOtpId).orElseThrow();
        registrationOtp.setAttempts(registrationOtp.getAttempts() + 1);
        registrationOtpRepository.save(registrationOtp);
    }
}
