package com.petsitter.application.dto.order;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** E10 每筆 payment（E2 摘要不含 accountSnapshot）。 */
public record PaymentView(UUID paymentId, String direction, String purpose, String method, int amount, String status,
                          OffsetDateTime dueAt, OffsetDateTime escalateAt, AccountSnapshot accountSnapshot,
                          UUID currentSubmissionId, OffsetDateTime createdAt, OffsetDateTime verifiedAt,
                          OffsetDateTime voidedAt, List<String> allowedActions) {

    public record AccountSnapshot(String bankCode, String branch, String accountNumber, String accountName) {
    }

    public PaymentView withoutSnapshot() {
        return new PaymentView(paymentId, direction, purpose, method, amount, status, dueAt, escalateAt, null,
                currentSubmissionId, createdAt, verifiedAt, voidedAt, allowedActions);
    }
}
