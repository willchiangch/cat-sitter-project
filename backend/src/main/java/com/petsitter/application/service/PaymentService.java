package com.petsitter.application.service;

import com.petsitter.application.dto.UpdateSitterPaymentInfoRequest;
import com.petsitter.domain.model.BankAccountInfo;
import com.petsitter.domain.model.Profile;
import com.petsitter.domain.repository.ProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 保母收款帳號設定（PRD-025，SD-SUPPLY 未重建）。SD-ORDER M11：舊付款流程（上傳 / 確認 / 駁回）已刪除，
 * 改由 SD-ORDER E11 / E14 / E15；本類別只保留帳號設定，訂單讀取經 {@code PayoutAccountQuery}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final ProfileRepository profileRepository;

    @Transactional
    public void updateSitterPaymentInfo(UUID sitterId, UpdateSitterPaymentInfoRequest request) {
        log.info("Updating payment info for sitter: {}", sitterId);
        Profile profile = profileRepository.findByUserIdAndType(sitterId, "SITTER")
                .orElseGet(() -> Profile.builder()
                        .userId(sitterId)
                        .type("SITTER")
                        .trustScore(100)
                        .kycStatus("UNVERIFIED")
                        .build());
        profile.setBankAccountInfo(BankAccountInfo.builder()
                .bankCode(request.bankCode())
                .bankBranch(request.bankBranch())
                .bankAccount(request.bankAccount())
                .bankPayeeName(request.bankPayeeName())
                .build());
        profileRepository.save(profile);
    }

    @Transactional(readOnly = true)
    public BankAccountInfo getSitterPaymentInfo(UUID sitterId) {
        BankAccountInfo info = profileRepository.findByUserIdAndType(sitterId, "SITTER")
                .map(Profile::getBankAccountInfo).orElse(null);
        return info == null ? BankAccountInfo.builder().build() : info;
    }
}
