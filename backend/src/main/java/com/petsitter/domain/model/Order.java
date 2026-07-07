package com.petsitter.domain.model;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Order extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sitter_id", nullable = false)
    private User sitter;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<OrderItem> items; // 存放原始預約內容 JSON

    @Column(nullable = false)
    private String status;

    @Builder.Default
    @Column(name = "total_amount", nullable = false)
    private Integer totalAmount = 0;

    @Builder.Default
    @Column(name = "adjustment_amount", nullable = false)
    private Integer adjustmentAmount = 0;

    @Column(name = "adjustment_reason")
    private String adjustmentReason;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "payout_at")
    private OffsetDateTime payoutAt;

    @Builder.Default
    @Column(name = "waiting_for_owner_action", nullable = false)
    private boolean waitingForOwnerAction = false;

    @Builder.Default
    @Column(name = "is_disputed", nullable = false)
    private boolean isDisputed = false;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "idempotency_key", unique = true)
    private String idempotencyKey;

    @Column(name = "payment_proof_url")
    private String paymentProofUrl;

    @Column(name = "payment_proof_last_five", length = 5)
    private String paymentProofLastFive;

    @Builder.Default
    @Column(name = "disclaimer_agreed", nullable = false)
    private boolean disclaimerAgreed = false;

    @Column(name = "disclaimer_agreed_at")
    private OffsetDateTime disclaimerAgreedAt;

    @Column(name = "payment_idempotency_key")
    private String paymentIdempotencyKey;

    @Builder.Default
    @Column(name = "media_expiry_warned", nullable = false)
    private boolean mediaExpiryWarned = false;
}

