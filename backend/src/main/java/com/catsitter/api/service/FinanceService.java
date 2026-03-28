package com.catsitter.api.service;

import com.catsitter.api.dto.payment.PayUniWebhookRequest;
import com.catsitter.api.entity.PaymentTransaction;
import com.catsitter.api.entity.SitterSubscription;
import com.catsitter.api.entity.SubscriptionPlan;
import com.catsitter.api.repository.PaymentTransactionRepository;
import com.catsitter.api.repository.SitterSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

@Service
public class FinanceService {

    private static final Logger logger = LoggerFactory.getLogger(FinanceService.class);

    private final PaymentTransactionRepository transactionRepository;
    private final SitterSubscriptionRepository subscriptionRepository;
    private final PayUniService payUniService;

    public FinanceService(PaymentTransactionRepository transactionRepository,
                          SitterSubscriptionRepository subscriptionRepository,
                          PayUniService payUniService) {
        this.transactionRepository = transactionRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.payUniService = payUniService;
    }

    @Transactional
    public String processPayUniWebhook(PayUniWebhookRequest request) {
        // 1. Verify Token
        if (!payUniService.verifyWebhookToken(request)) {
            logger.warn("Invalid PayUni token for MerTradeNo: {}", request.MerTradeNo());
            return "0|InvalidToken";
        }

        // 2. Find Transaction
        Optional<PaymentTransaction> txOpt = transactionRepository.findByMerTradeNo(request.MerTradeNo());
        if (txOpt.isEmpty()) {
            logger.warn("Transaction not found for MerTradeNo: {}", request.MerTradeNo());
            return "0|TransactionNotFound";
        }

        PaymentTransaction tx = txOpt.get();
        if ("SUCCESS".equals(tx.getStatus())) {
            return "1|OK"; // Idempotency
        }

        // 3. Update Status
        tx.setTradeNo(request.TradeNo());
        tx.setPaymentType(request.PaymentType());
        tx.setPayTime(request.Timestamp() != null ? Instant.ofEpochSecond(Long.parseLong(request.Timestamp())) : Instant.now());

        if ("1".equals(request.Status())) {
            tx.setStatus("SUCCESS");
            
            // 4. Handle Subscription (if applicable)
            if (tx.getSubscription() != null) {
                activateSubscription(tx.getSubscription());
            }
            
            logger.info("Payment successful for MerTradeNo: {}", request.MerTradeNo());
        } else {
            tx.setStatus("FAILED");
            logger.info("Payment failed for MerTradeNo: {}", request.MerTradeNo());
        }

        transactionRepository.save(tx);
        return "1|OK";
    }

    private void activateSubscription(SitterSubscription sub) {
        LocalDate now = LocalDate.now();
        
        // If current sub is still active, extend it. Otherwise, start from now.
        if (sub.getEndDate() != null && sub.getEndDate().isAfter(now)) {
            sub.setEndDate(sub.getEndDate().plusMonths(1)); // Default to monthly extension for MVP
        } else {
            sub.setStartDate(now);
            sub.setEndDate(now.plusMonths(1));
        }
        
        sub.setStatus("ACTIVE");
        subscriptionRepository.save(sub);
        logger.info("Subscription activated for Sitter: {} until {}", sub.getSitterProfile().getId(), sub.getEndDate());
    }
}
