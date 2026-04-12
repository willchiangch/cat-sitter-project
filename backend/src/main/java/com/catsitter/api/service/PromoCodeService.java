package com.catsitter.api.service;

import com.catsitter.api.entity.PromoCode;
import com.catsitter.api.repository.PromoCodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;

@Service
public class PromoCodeService {

    private final PromoCodeRepository promoCodeRepository;

    public PromoCodeService(PromoCodeRepository promoCodeRepository) {
        this.promoCodeRepository = promoCodeRepository;
    }

    public Optional<PromoCode> validatePromoCode(String code) {
        if (code == null || code.isBlank()) return Optional.empty();

        Optional<PromoCode> promo = promoCodeRepository.findByCode(code.trim().toUpperCase());
        if (promo.isEmpty()) return Optional.empty();

        PromoCode p = promo.get();
        if (p.getExpiryDate() != null && p.getExpiryDate().isBefore(Instant.now())) {
            return Optional.empty();
        }
        if (p.getUsedCount() >= p.getMaxUses()) {
            return Optional.empty();
        }
        return Optional.of(p);
    }

    /**
     * 根據折扣碼計算最終金額，最低為 0
     */
    public BigDecimal calculateFinalAmount(PromoCode promo, BigDecimal originalAmount) {
        BigDecimal discount;
        if ("PERCENT".equals(promo.getDiscountType()) && promo.getDiscountPercent() != null) {
            discount = originalAmount
                    .multiply(promo.getDiscountPercent())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        } else {
            discount = promo.getDiscountAmount() != null ? promo.getDiscountAmount() : BigDecimal.ZERO;
        }
        BigDecimal finalAmount = originalAmount.subtract(discount);
        return finalAmount.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : finalAmount;
    }

    /**
     * 取得實際折扣金額（考慮折扣後不能低於0）
     */
    public BigDecimal calculateDiscountAmount(PromoCode promo, BigDecimal originalAmount) {
        BigDecimal finalAmount = calculateFinalAmount(promo, originalAmount);
        return originalAmount.subtract(finalAmount);
    }

    @Transactional
    public void incrementUsedCount(String code) {
        promoCodeRepository.findByCode(code.trim().toUpperCase()).ifPresent(p -> {
            p.setUsedCount(p.getUsedCount() + 1);
            promoCodeRepository.save(p);
        });
    }
}
