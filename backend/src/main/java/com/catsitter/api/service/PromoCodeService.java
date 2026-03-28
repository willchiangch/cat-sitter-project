package com.catsitter.api.service;

import com.catsitter.api.entity.PromoCode;
import com.catsitter.api.repository.PromoCodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional
    public void incrementUsedCount(String code) {
        promoCodeRepository.findByCode(code.trim().toUpperCase()).ifPresent(p -> {
            p.setUsedCount(p.getUsedCount() + 1);
            promoCodeRepository.save(p);
        });
    }
}
