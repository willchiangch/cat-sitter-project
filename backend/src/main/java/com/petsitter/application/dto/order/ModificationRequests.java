package com.petsitter.application.dto.order;

import java.math.BigDecimal;
import java.util.List;

/** E17 / E20 / E21 request（型別規則見 SD-ORDER §6.1）。 */
public final class ModificationRequests {

    private ModificationRequests() {
    }

    public record AddItem(String date, String planId, BigDecimal dailyCount, List<String> petIds) {
    }

    /** E17：type、reason、removeVisitIds、addItems、（SITTER）加減與原因。 */
    public record Initiate(String type, String reason, List<String> removeVisitIds, List<AddItem> addItems,
                           BigDecimal surchargeAmount, BigDecimal discountAmount, String adjustmentReason) {
    }

    /** E20。 */
    public record Quote(List<String> removeVisitIds, List<AddItem> addItems, BigDecimal surchargeAmount,
                        BigDecimal discountAmount, String adjustmentReason) {
    }

    public record Reject(String reason) {
    }

    public record Description(String description) {
    }

    public record RefundDecision(String paymentId, Boolean exported) {
    }

    public record Adjustment(BigDecimal amount, String basis) {
    }

    /** E29。 */
    public record Mediation(List<RefundDecision> refundDecisions, Adjustment adjustment) {
    }

    /** E16。 */
    public record Void(String reason) {
    }
}
