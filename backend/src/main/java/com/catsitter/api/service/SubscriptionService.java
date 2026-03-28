package com.catsitter.api.service;

import com.catsitter.api.entity.*;
import com.catsitter.api.entity.enums.RoleType;
import com.catsitter.api.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SubscriptionService {

    private final SubscriptionPlanRepository planRepository;
    private final SitterSubscriptionRepository subscriptionRepository;
    private final ProfileRepository profileRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final PayUniService payUniService;
    private final PromoCodeService promoCodeService;

    public SubscriptionService(SubscriptionPlanRepository planRepository,
                               SitterSubscriptionRepository subscriptionRepository,
                               ProfileRepository profileRepository,
                               PaymentTransactionRepository transactionRepository,
                               PayUniService payUniService,
                               PromoCodeService promoCodeService) {
        this.planRepository = planRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.profileRepository = profileRepository;
        this.transactionRepository = transactionRepository;
        this.payUniService = payUniService;
        this.promoCodeService = promoCodeService;
    }

    public List<SubscriptionPlan> getAllPlans() {
        return planRepository.findAll();
    }

    @Transactional
    public Map<String, String> createCheckoutParams(Account account, String planName, String promoCode) {
        Profile profile = profileRepository.findByAccountIdAndRoleType(account.getId(), RoleType.SITTER)
                .orElseThrow(() -> new RuntimeException("Sitter profile not found"));

        SubscriptionPlan plan = planRepository.findByName(planName)
                .orElseThrow(() -> new RuntimeException("Plan not found: " + planName));

        BigDecimal amount = plan.getMonthlyPrice(); // Default to monthly for MVP
        
        // Apply Promo Code if exists
        if (promoCode != null && !promoCode.isBlank()) {
            promoCodeService.validatePromoCode(promoCode).ifPresent(p -> {
                // Apply discount (simplification)
                // In a real app, you'd calculate final amount
                promoCodeService.incrementUsedCount(promoCode);
            });
        }

        // 1. Find or Create SitterSubscription (PENDING)
        SitterSubscription sub = subscriptionRepository.findBySitterProfileAndStatus(profile, "ACTIVE")
                .orElseGet(() -> {
                   SitterSubscription newSub = new SitterSubscription();
                   newSub.setSitterProfile(profile);
                   newSub.setPlan(plan);
                   newSub.setStatus("PENDING_PAYMENT");
                   return subscriptionRepository.save(newSub);
                });

        // 2. Create PaymentTransaction
        String merTradeNo = "SUB_" + UUID.randomUUID().toString().substring(0, 18);
        PaymentTransaction tx = new PaymentTransaction();
        tx.setAccount(account);
        tx.setSubscription(sub);
        tx.setMerTradeNo(merTradeNo);
        tx.setAmount(amount);
        tx.setStatus("PENDING");
        transactionRepository.save(tx);

        // 3. Generate PayUni UPP Params
        return payUniService.generateUppParams(
                merTradeNo,
                amount.intValue(),
                "Cat Sitter Subscription: " + planName,
                "https://catsitter.example/api/v1/payments/payuni/webhook", // Should be real public domain
                "https://catsitter.example/payments/success"
        );
    }
}
