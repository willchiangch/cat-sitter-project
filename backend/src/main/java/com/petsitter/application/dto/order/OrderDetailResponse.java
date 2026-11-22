package com.petsitter.application.dto.order;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** E2 / E27 response（SD-ORDER §6.3）。null 欄位不輸出。 */
public record OrderDetailResponse(UUID orderId, String status, String role, Counterpart counterpart,
                                  LocalDate firstServiceDate, LocalDate lastServiceDate, OffsetDateTime reviewExpiresAt,
                                  int baseAmount, Quote quote, String sitterTier, String modificationPricingMode,
                                  List<Group> groups, List<VisitItem> visits, String questionnaireStatus,
                                  String questionnaireExemptReason, List<PaymentView> payments,
                                  OpenModification openModification, Cancellation cancellation,
                                  OffsetDateTime completedAt, OffsetDateTime disputeWindowEndsAt, Integer netPaid,
                                  OffsetDateTime serverTime, CompletionHint completionHint, List<String> allowedActions,
                                  // E27 only
                                  List<ModificationSummary> modifications, List<AdminTaskItem> adminTasks,
                                  List<LogItem> logs) {

    public record Counterpart(UUID userId, String displayName) {
    }

    public record Quote(Original original, List<Adjustment> adjustments, Integer totalAmount, OffsetDateTime quotedAt) {
    }

    public record Original(int baseAmount, Integer surchargeAmount, Integer discountAmount, String pricingNotes,
                           Integer totalAmount, OffsetDateTime quotedAt) {
    }

    public record Adjustment(UUID modificationId, String kind, String type, OffsetDateTime effectiveAt,
                             List<LocalDate> removedVisitDates, List<AddedItem> addedItems, int totalBefore,
                             int totalAfter, int amountChange) {
    }

    public record AddedItem(String date, String planTitle, int dailyCount) {
    }

    public record Group(UUID orderItemId, String source, UUID planId, String planTitle, Integer unitPrice,
                        LocalDate bookableFrom, LocalDate bookableTo, List<LocalDate> dates, int dailyCount,
                        List<Pet> pets) {
    }

    public record Pet(UUID petId, String name, String type, boolean deleted) {
    }

    public record VisitItem(UUID visitId, LocalDate serviceDate, UUID planId, String status, OffsetDateTime completedAt,
                            boolean careLogSubmitted) {
    }

    public record OpenModification(UUID modificationId, String status, String initiatedBy) {
    }

    public record Cancellation(String cancelledBy, String reasonCode, String reason, OffsetDateTime cancelledAt) {
    }

    public record CompletionHint(String code, OffsetDateTime autoCompleteAt) {
    }

    public record AdminTaskItem(UUID taskId, String type, String status, String resolution, OffsetDateTime createdAt) {
    }

    public record LogItem(String actionType, String operatorRole, UUID operatorUserId, Object payload,
                          OffsetDateTime createdAt) {
    }
}
