package com.petsitter.interfaces.controller;

import com.petsitter.application.dto.UpdateSitterPaymentInfoRequest;
import com.petsitter.application.service.PaymentService;
import com.petsitter.domain.model.BankAccountInfo;
import com.petsitter.infrastructure.security.TokenContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/sitter/payment-info")
@RequiredArgsConstructor
public class SitterProfileController {

    private final PaymentService paymentService;

    /**
     * 保母更新轉帳帳戶資訊 (Sitter)
     */
    @PreAuthorize("hasRole('SITTER')")
    @PutMapping
    public ResponseEntity<Map<String, String>> updatePaymentInfo(
            @Valid @RequestBody UpdateSitterPaymentInfoRequest request) {
        
        UUID sitterId = TokenContext.getUserId();
        paymentService.updateSitterPaymentInfo(sitterId, request);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "銀行轉帳帳戶資訊更新成功"));
    }

    /**
     * 保母取得個人轉帳帳戶資訊 (Sitter)
     */
    @PreAuthorize("hasRole('SITTER')")
    @GetMapping
    public ResponseEntity<BankAccountInfo> getPaymentInfo() {
        UUID sitterId = TokenContext.getUserId();
        log.info("Getting payment info for sitter: {}", sitterId);
        return ResponseEntity.ok(paymentService.getSitterPaymentInfo(sitterId));
    }
}
