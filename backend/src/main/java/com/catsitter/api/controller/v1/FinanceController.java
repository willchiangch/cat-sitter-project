package com.catsitter.api.controller.v1;

import com.catsitter.api.dto.payment.MockPayUniRequest;
import com.catsitter.api.dto.payment.PayUniWebhookRequest;
import com.catsitter.api.dto.payment.SitterFinanceResponse;
import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.PaymentTransaction;
import com.catsitter.api.repository.PaymentTransactionRepository;
import com.catsitter.api.service.FinanceService;
import com.catsitter.api.service.PayUniService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments/payuni")
public class FinanceController {

    private final FinanceService financeService;
    private final PayUniService payUniService;
    private final PaymentTransactionRepository transactionRepository;

    @Value("${application.payuni.mer-id:}")
    private String merId;

    public FinanceController(FinanceService financeService,
                             PayUniService payUniService,
                             PaymentTransactionRepository transactionRepository) {
        this.financeService = financeService;
        this.payUniService = payUniService;
        this.transactionRepository = transactionRepository;
    }

    @GetMapping("/sitter-summary")
    public ResponseEntity<SitterFinanceResponse> getSitterFinanceSummary(@AuthenticationPrincipal Account account) {
        return ResponseEntity.ok(financeService.getSitterFinanceSummary(account));
    }

    /**
     * Real PayUni Webhook (NotifyURL)
     * Note: Form URL Encoded mapping is handled by Spring for POJO DTOs
     */
    @PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String handleWebhook(PayUniWebhookRequest request) {
        return financeService.processPayUniWebhook(request);
    }

    /**
     * Mock Callback for testing - simulates a correctly signed PayUni request.
     * Use this in Swagger or Postman to trigger a success/failure state change.
     */
    @PostMapping("/mock-callback")
    public String handleMockCallback(@RequestBody MockPayUniRequest mockReq) {
        Optional<PaymentTransaction> tx = transactionRepository.findByMerTradeNo(mockReq.merTradeNo());
        if (tx.isEmpty()) return "0|NotFound";

        PaymentTransaction transaction = tx.get();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String tradeNo = "MOCK_PAY_UNI_" + timestamp;
        
        // We use generateUppParams to get a valid Token for our mock request
        Map<String, String> signedParams = payUniService.generateUppParams(
                mockReq.merTradeNo(),
                transaction.getAmount().intValue(),
                "Mock Subscription Payment",
                "/webhook",
                "/return"
        );
        
        // Manually inject the 'Status' we want into the signed payload logic
        // PayUni verifyToken sorts all params, so we must be consistent.
        PayUniWebhookRequest signedRequest = new PayUniWebhookRequest(
                merId,
                mockReq.merTradeNo(),
                tradeNo,
                transaction.getAmount().toString(),
                mockReq.status(),
                "CVS", 
                signedParams.get("Token"), // This token might need adjustment if Status is in signature
                timestamp
        );

        // Note: The Token in signedParams was generated WITHOUT 'Status'.
        // If PayUni signature includes 'Status' in the webhook, we'd need a specialized calculateToken.
        // For now, satisfy the simplest verification.
        
        return financeService.processPayUniWebhook(signedRequest);
    }
}
