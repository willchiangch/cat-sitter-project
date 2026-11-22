package com.petsitter.application.dto.order;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** E17–E33 response（SD-ORDER §6.3）。 */
public final class ModificationResponses {

    private ModificationResponses() {
    }

    public record Initiated(UUID modificationId, String status, String initiatedBy, OffsetDateTime expiresAt,
                            Integer systemAmount, Integer newTotalAmount, Integer netPaid, Integer diff) {
    }

    public record Quoted(UUID modificationId, String status, int systemAmount, int newTotalAmount, int netPaid, int diff,
                         OffsetDateTime expiresAt) {
    }

    /** E32 / E33 變更金額試算（唯讀；{@code pricingMode} 只在 SITTER 呼叫時輸出，null 不輸出）。 */
    public record Preview(String type, List<UUID> removeVisitIds, PreviewBreakdown breakdown, int systemAmount,
                          int surchargeAmount, int discountAmount, int newTotalAmount, int currentTotalAmount, int netPaid,
                          int diff, String pricingMode, OffsetDateTime computedAt) {
    }

    public record PreviewBreakdown(List<PreviewKept> kept, List<PreviewAdded> added) {
    }

    public record PreviewKept(UUID visitId, UUID planId, LocalDate serviceDate, String status, int unitPrice) {
    }

    public record PreviewAdded(int index, String date, UUID planId, int dailyCount, int unitPrice, int subtotal) {
    }

    public record Ended(UUID modificationId, String status, OffsetDateTime endedAt) {
    }

    public record PaymentRef(UUID paymentId, String direction, String purpose, int amount, String status, OffsetDateTime dueAt) {
    }

    public record Accepted(UUID modificationId, String status, int netPaid, int diff, PaymentRef payment, String orderStatus,
                           Integer totalAmount) {
    }

    public record Content(List<UUID> removeVisitIds, List<AddItem> addItems) {
    }

    public record AddItem(String date, UUID planId, int dailyCount, List<UUID> petIds) {
    }

    public record Detail(UUID modificationId, String type, String status, String initiatedBy, String reason,
                         Content requestedContent, Content quotedContent, Integer systemAmount, Integer surchargeAmount,
                         Integer discountAmount, String adjustmentReason, Integer newTotalAmount, Integer netPaid,
                         Integer diff, OffsetDateTime expiresAt, OffsetDateTime endedAt, String rejectReason,
                         UUID sourceModificationId, PaymentRef payment, OffsetDateTime disputeAvailableAt,
                         List<String> allowedActions) {
    }

    public record Disputed(UUID orderId, String status, UUID adminTaskId) {
    }

    public record Completed(UUID orderId, String status, OffsetDateTime completedAt) {
    }

    public record AdminVoided(UUID orderId, String status, String cancelledBy, String paymentStatus) {
    }

    public record Mediated(UUID orderId, String status, List<UUID> adjustmentPaymentIds) {
    }

    public record AdminTaskItem(UUID taskId, String type, UUID orderId, UUID paymentId, String status, OffsetDateTime createdAt) {
    }

    public record Ledger(String month, String serverMonth, int totalIncome, List<LedgerOrder> orders) {
    }

    public record LedgerOrder(UUID orderId, String status, String ownerDisplayName, LocalDate firstServiceDate,
                              LocalDate lastServiceDate, OffsetDateTime terminalAt, int netPaid) {
    }
}
