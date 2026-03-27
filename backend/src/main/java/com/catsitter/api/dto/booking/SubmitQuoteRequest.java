package com.catsitter.api.dto.booking;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record SubmitQuoteRequest(
    @NotNull
    @PositiveOrZero
    BigDecimal baseAmount,

    @NotNull
    @PositiveOrZero
    BigDecimal surchargeAmount,

    @NotNull
    @PositiveOrZero
    BigDecimal discountAmount,

    String pricingNotes
) {}
