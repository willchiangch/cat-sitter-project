package com.catsitter.api.service;

import com.catsitter.api.dto.sitter.SitterSubscriptionDTO;
import com.catsitter.api.dto.subscription.PromoValidationResponse;
import com.catsitter.api.entity.*;
import com.catsitter.api.entity.enums.RoleType;
import com.catsitter.api.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final SubscriptionPlanRepository planRepository;
    private final SitterSubscriptionRepository subscriptionRepository;
    private final ProfileRepository profileRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final PayUniService payUniService;
    private final PromoCodeService promoCodeService;
    private final SubscriptionChangeLogRepository changeLogRepository;

    public SubscriptionService(SubscriptionPlanRepository planRepository,
                               SitterSubscriptionRepository subscriptionRepository,
                               ProfileRepository profileRepository,
                               PaymentTransactionRepository transactionRepository,
                               PayUniService payUniService,
                               PromoCodeService promoCodeService,
                               SubscriptionChangeLogRepository changeLogRepository) {
        this.planRepository = planRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.profileRepository = profileRepository;
        this.transactionRepository = transactionRepository;
        this.payUniService = payUniService;
        this.promoCodeService = promoCodeService;
        this.changeLogRepository = changeLogRepository;
    }

    public List<SubscriptionPlan> getAllPlans() {
        return planRepository.findAll();
    }

    // ─── Promo Validation ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PromoValidationResponse validatePromo(String planCode, String promoCode) {
        SubscriptionPlan plan = planRepository.findByPlanCode(planCode)
                .orElseThrow(() -> new RuntimeException("找不到方案: " + planCode));

        BigDecimal original = plan.getMonthlyPrice();

        if (promoCode == null || promoCode.isBlank()) {
            return new PromoValidationResponse(false, "請輸入折扣碼", original, BigDecimal.ZERO, original);
        }

        Optional<PromoCode> promoOpt = promoCodeService.validatePromoCode(promoCode);
        if (promoOpt.isEmpty()) {
            return new PromoValidationResponse(false, "折扣碼無效或已過期", original, BigDecimal.ZERO, original);
        }

        PromoCode promo = promoOpt.get();
        BigDecimal discount = promoCodeService.calculateDiscountAmount(promo, original);
        BigDecimal finalAmount = promoCodeService.calculateFinalAmount(promo, original);

        return new PromoValidationResponse(true, "折扣碼有效", original, discount, finalAmount);
    }

    // ─── Checkout (PayUni flow) ───────────────────────────────────────────────

    @Transactional
    public Map<String, String> createCheckoutParams(Account account, String planName, String promoCode) {
        Profile profile = profileRepository.findByAccountIdAndRoleType(account.getId(), RoleType.SITTER)
                .orElseThrow(() -> new RuntimeException("Sitter profile not found"));

        SubscriptionPlan plan = planRepository.findByName(planName)
                .orElseThrow(() -> new RuntimeException("Plan not found: " + planName));

        BigDecimal amount = plan.getMonthlyPrice();

        if (promoCode != null && !promoCode.isBlank()) {
            promoCodeService.validatePromoCode(promoCode).ifPresent(p -> {
                promoCodeService.incrementUsedCount(promoCode);
            });
        }

        SitterSubscription sub = subscriptionRepository.findBySitterProfileAndStatus(profile, "ACTIVE")
                .orElseGet(() -> {
                    SitterSubscription newSub = new SitterSubscription();
                    newSub.setSitterProfile(profile);
                    newSub.setPlan(plan);
                    newSub.setStatus("PENDING_PAYMENT");
                    return subscriptionRepository.save(newSub);
                });

        String merTradeNo = "SUB_" + UUID.randomUUID().toString().substring(0, 18);
        PaymentTransaction tx = new PaymentTransaction();
        tx.setAccount(account);
        tx.setSubscription(sub);
        tx.setMerTradeNo(merTradeNo);
        tx.setAmount(amount);
        tx.setStatus("PENDING");
        transactionRepository.save(tx);

        return payUniService.generateUppParams(
                merTradeNo,
                amount.intValue(),
                "Cat Sitter Subscription: " + planName,
                "https://catsitter.example/api/v1/payments/payuni/webhook",
                "https://catsitter.example/payments/success"
        );
    }

    // ─── Get Current ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public SitterSubscriptionDTO getCurrentSubscription(Account account) {
        Profile profile = profileRepository.findByAccountIdAndRoleType(account.getId(), RoleType.SITTER)
                .orElseThrow(() -> new RuntimeException("Sitter profile not found"));

        SitterSubscription sub = subscriptionRepository.findBySitterProfileIdAndStatus(profile.getId(), "ACTIVE")
                .orElseGet(() -> subscriptionRepository
                        .findTopBySitterProfileIdOrderByCreatedAtDesc(profile.getId())
                        .orElse(null));

        if (sub == null) {
            return new SitterSubscriptionDTO("FREE", "ACTIVE", null);
        }
        return new SitterSubscriptionDTO(
                sub.getPlan().getPlanCode(),
                sub.getStatus(),
                sub.getEndDate()
        );
    }

    // ─── Change Plan ──────────────────────────────────────────────────────────

    /**
     * 變更訂閱方案，支援折扣碼。
     * 若 finalAmount <= 0 → 直接生效（不走付款流程）。
     */
    @Transactional
    public SitterSubscriptionDTO changePlan(Account account, String planCode, String promoCode) {
        if (planCode == null || planCode.isBlank()) {
            throw new RuntimeException("請提供有效的方案代碼");
        }

        Profile profile = profileRepository.findByAccountIdAndRoleType(account.getId(), RoleType.SITTER)
                .orElseThrow(() -> new RuntimeException("找不到保母檔案，請確認登入狀態"));

        SubscriptionPlan plan = planRepository.findByPlanCode(planCode)
                .orElseThrow(() -> new RuntimeException("系統找不到指定的方案代碼: [" + planCode + "]，請聯繫管理員或確認方案清單"));

        Optional<SitterSubscription> currentOpt =
                subscriptionRepository.findBySitterProfileIdAndStatus(profile.getId(), "ACTIVE");

        if (currentOpt.isPresent() && currentOpt.get().getPlan().getPlanCode().equals(planCode)) {
            return new SitterSubscriptionDTO(planCode, "ACTIVE", currentOpt.get().getEndDate());
        }

        String fromPlanCode = currentOpt.map(s -> s.getPlan().getPlanCode()).orElse(null);

        // ── 計算折扣 ──
        BigDecimal originalAmount = plan.getMonthlyPrice();
        BigDecimal discountAmount = BigDecimal.ZERO;
        BigDecimal finalAmount = originalAmount;
        PromoCode usedPromo = null;

        if (promoCode != null && !promoCode.isBlank()) {
            Optional<PromoCode> promoOpt = promoCodeService.validatePromoCode(promoCode);
            if (promoOpt.isPresent()) {
                usedPromo = promoOpt.get();
                discountAmount = promoCodeService.calculateDiscountAmount(usedPromo, originalAmount);
                finalAmount = promoCodeService.calculateFinalAmount(usedPromo, originalAmount);
                promoCodeService.incrementUsedCount(promoCode);
                log.info("[SUBSCRIPTION] Promo {} applied: -{} (final={})", promoCode, discountAmount, finalAmount);
            } else {
                log.warn("[SUBSCRIPTION] Invalid promo code '{}' submitted for plan change, ignored", promoCode);
            }
        }

        // ── 決定 changeType ──
        String changeType;
        if (finalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            changeType = "FREE_REDEMPTION";
        } else if (fromPlanCode == null) {
            changeType = "SUBSCRIBE";
        } else {
            // 簡單以 plan price 判斷升降
            changeType = originalAmount.compareTo(
                    currentOpt.map(s -> s.getPlan().getMonthlyPrice()).orElse(BigDecimal.ZERO)) >= 0
                    ? "UPGRADE" : "DOWNGRADE";
        }

        // ── 更新訂閱 ──
        SitterSubscription sub = currentOpt.orElseGet(() -> {
            SitterSubscription newSub = new SitterSubscription();
            newSub.setSitterProfile(profile);
            newSub.setStartDate(LocalDate.now());
            return newSub;
        });

        sub.setPlan(plan);
        sub.setStatus("ACTIVE");
        if (sub.getStartDate() == null) sub.setStartDate(LocalDate.now());
        sub.setEndDate(LocalDate.now().plusDays(30));
        subscriptionRepository.save(sub);

        // ── 寫 log ──
        SubscriptionChangeLog changeLog = new SubscriptionChangeLog();
        changeLog.setSitterProfile(profile);
        changeLog.setFromPlanCode(fromPlanCode);
        changeLog.setToPlanCode(planCode);
        changeLog.setChangeType(changeType);
        changeLog.setOriginalAmount(originalAmount);
        changeLog.setDiscountAmount(discountAmount);
        changeLog.setFinalAmount(finalAmount);
        if (usedPromo != null) {
            changeLog.setPromoCode(usedPromo);
            changeLog.setPromoCodeUsed(usedPromo.getCode());
        }
        if ("FREE_REDEMPTION".equals(changeType)) {
            changeLog.setNote("折扣碼使金額歸零，直接免費啟用");
        }
        changeLogRepository.save(changeLog);

        log.info("[SUBSCRIPTION] {} -> {} ({}), original={} discount={} final={}",
                fromPlanCode, planCode, changeType, originalAmount, discountAmount, finalAmount);

        return new SitterSubscriptionDTO(plan.getPlanCode(), "ACTIVE", sub.getEndDate());
    }

    // ─── Cancel ──────────────────────────────────────────────────────────────

    @Transactional
    public void cancelSubscription(Account account) {
        Profile profile = profileRepository.findByAccountIdAndRoleType(account.getId(), RoleType.SITTER)
                .orElseThrow(() -> new RuntimeException("Sitter profile not found"));

        subscriptionRepository.findBySitterProfileIdAndStatus(profile.getId(), "ACTIVE")
                .ifPresent(sub -> {
                    String fromPlanCode = sub.getPlan().getPlanCode();
                    sub.setStatus("CANCELLED");
                    subscriptionRepository.save(sub);

                    SubscriptionChangeLog changeLog = new SubscriptionChangeLog();
                    changeLog.setSitterProfile(profile);
                    changeLog.setFromPlanCode(fromPlanCode);
                    changeLog.setToPlanCode("FREE");
                    changeLog.setChangeType("CANCEL");
                    changeLog.setOriginalAmount(BigDecimal.ZERO);
                    changeLog.setDiscountAmount(BigDecimal.ZERO);
                    changeLog.setFinalAmount(BigDecimal.ZERO);
                    changeLogRepository.save(changeLog);

                    log.info("[SUBSCRIPTION] Cancelled plan {} for sitter {}", fromPlanCode, profile.getId());
                });
    }
}
