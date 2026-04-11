package com.catsitter.api.service;

import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.VerificationCode;
import com.catsitter.api.repository.AccountRepository;
import com.catsitter.api.repository.VerificationCodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Random;

@Service
public class EmailVerificationService {

    private final VerificationCodeRepository verificationCodeRepository;
    private final AccountRepository accountRepository;
    private final EmailService emailService;
    private final Random random = new Random();

    public EmailVerificationService(
            VerificationCodeRepository verificationCodeRepository,
            AccountRepository accountRepository,
            EmailService emailService) {
        this.verificationCodeRepository = verificationCodeRepository;
        this.accountRepository = accountRepository;
        this.emailService = emailService;
    }

    @Transactional
    public void sendVerificationCode(Account account) {
        // Cleanup old codes
        verificationCodeRepository.deleteByAccount(account);

        // Generate 6-digit code
        String code = String.format("%06d", random.nextInt(999999));
        
        VerificationCode verificationCode = new VerificationCode();
        verificationCode.setAccount(account);
        verificationCode.setCode(code);
        verificationCode.setExpiryAt(LocalDateTime.now().plusMinutes(10));
        
        verificationCodeRepository.save(verificationCode);
        System.out.println("[VERIFY] Generated code " + code + " for email: " + account.getEmail());

        // Send Email
        String htmlContent = String.format(
            "<div style='font-family: sans-serif; padding: 20px; border: 1px solid #eee; border-radius: 10px;'>" +
            "<h2>WhiskerWatch 驗證碼</h2>" +
            "<p>您好，感謝您使用 WhiskerWatch 貓咪保姆媒合平台。</p>" +
            "<p>請輸入下方的驗證碼以完成信箱綁定：</p>" +
            "<div style='font-size: 32px; font-weight: bold; color: #FF6B6B; letter-spacing: 5px; margin: 20px 0;'>%s</div>" +
            "<p>驗證碼將在 10 分鐘後失效。若非本人操作，請忽略此郵件。</p>" +
            "<hr />" +
            "<p style='font-size: 12px; color: #999;'>WhiskerWatch Team</p>" +
            "</div>", code
        );

        emailService.sendEmail(account.getEmail(), "WhiskerWatch 通訊信箱驗證碼", htmlContent);
    }

    @Transactional
    public boolean verifyCode(Account account, String code) {
        Optional<VerificationCode> codeOpt = verificationCodeRepository.findByAccountAndCode(account, code);
        
        if (codeOpt.isPresent()) {
            VerificationCode verificationCode = codeOpt.get();
            if (!verificationCode.isExpired() && verificationCode.getUsedAt() == null) {
                verificationCode.setUsedAt(LocalDateTime.now());
                verificationCodeRepository.save(verificationCode);
                
                account.setEmailVerified(true);
                accountRepository.save(account);
                return true;
            }
        }
        return false;
    }
}
