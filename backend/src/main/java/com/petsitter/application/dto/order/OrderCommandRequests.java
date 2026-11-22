package com.petsitter.application.dto.order;

import java.math.BigDecimal;

/** E6–E9、E14、E15 request（型別規則見 SD-ORDER §6.1：金額 BigDecimal、enum / UUID 為 String）。 */
public final class OrderCommandRequests {

    private OrderCommandRequests() {
    }

    public record Quote(BigDecimal expectedAmount, BigDecimal surchargeAmount, BigDecimal discountAmount,
                        String pricingNotes) {
    }

    public record Reason(String reason) {
    }

    public record Withdraw(String expectedStatus, String reason) {
    }

    public record Verify(String submissionId) {
    }

    public record Reject(String submissionId, String reason) {
    }
}
