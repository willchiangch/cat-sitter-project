package com.petsitter.domain.model.order;

import com.petsitter.domain.model.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 每一筆應收 / 應退款項（SD-ORDER §5.1.1 第 8 段、§3.2）。 */
@Entity
@Table(name = "payments")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@ToString(exclude = "payeeAccountSnapshot")
public class Payment extends VersionedEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "modification_request_id", updatable = false)
    private UUID modificationRequestId;

    @Column(name = "source_payment_id", updatable = false)
    private UUID sourcePaymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PaymentDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PaymentPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PaymentMethod method;

    @Column(nullable = false, updatable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "due_at")
    private OffsetDateTime dueAt;

    @Column(name = "remind_at")
    private OffsetDateTime remindAt;

    @Column(name = "reminded_at")
    private OffsetDateTime remindedAt;

    @Column(name = "escalate_at")
    private OffsetDateTime escalateAt;

    @Column(name = "escalated_at")
    private OffsetDateTime escalatedAt;

    /** AES-256-GCM 密文（四欄 JSON）；明文不落 log。 */
    @Column(name = "payee_account_snapshot", updatable = false)
    private String payeeAccountSnapshot;

    @Column(name = "payee_account_key_version", updatable = false)
    private Short payeeAccountKeyVersion;

    @Column(name = "adjustment_basis", length = 500, updatable = false)
    private String adjustmentBasis;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "verified_by")
    private UUID verifiedBy;

    @Column(name = "voided_at")
    private OffsetDateTime voidedAt;

    @Column(name = "voided_by")
    private UUID voidedBy;

    @Column(name = "void_reason_code", length = 24)
    private String voidReasonCode;

    @Column(name = "void_reason", length = 200)
    private String voidReason;

    /** 建立者由 Service 明確寫入（SYSTEM 為 NULL），不用 @CreatedBy（SD-GLOBAL-SPEC §2.5）。 */
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    public boolean isOpen() {
        return status == PaymentStatus.UNPAID || status == PaymentStatus.SUBMITTED;
    }
}
