package com.petsitter.application.dto.order;

import java.time.OffsetDateTime;
import java.util.UUID;

/** E6–E9、E11、E13–E15 response。 */
public final class OrderCommandResponses {

    private OrderCommandResponses() {
    }

    public record Quote(UUID orderId, String status, int baseAmount, int surchargeAmount, int discountAmount,
                        int totalAmount, OffsetDateTime quotedAt, PaymentRef payment) {
    }

    public record PaymentRef(UUID paymentId, int amount, OffsetDateTime dueAt, OffsetDateTime escalateAt) {
    }

    public record Cancelled(UUID orderId, String status, String cancelledBy) {
    }

    public record Submitted(UUID submissionId, UUID paymentId, String paymentStatus, OffsetDateTime submittedAt) {
    }

    public record SubmissionItem(UUID submissionId, String status, String lastFive, OffsetDateTime submittedAt,
                                 OffsetDateTime disclaimerAgreedAt, OffsetDateTime reviewedAt, OffsetDateTime verifiedAt,
                                 String rejectReason, boolean voided) {
    }

    public record ProofUrl(String url, OffsetDateTime expiresAt) {
    }

    public record Verified(UUID paymentId, String paymentStatus, String orderStatus, ModificationRef modification) {
    }

    public record ModificationRef(UUID modificationId, String status) {
    }

    public record Rejected(UUID paymentId, String paymentStatus, UUID submissionId, String submissionStatus) {
    }
}
