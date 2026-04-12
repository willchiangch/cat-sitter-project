package com.catsitter.api.dto.subscription;

import java.math.BigDecimal;

public record PromoValidationResponse(
        boolean valid,
        String message,
        BigDecimal originalAmount,
        BigDecimal discountAmount,
        BigDecimal finalAmount
) {}
