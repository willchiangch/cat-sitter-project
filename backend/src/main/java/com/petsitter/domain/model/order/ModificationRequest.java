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
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** 變更請求（SD-ORDER §5.1.1 第 2 段、§3.3）。 */
@Entity
@Table(name = "modification_requests")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class ModificationRequest extends VersionedEntity {

    public static final Set<ModificationStatus> OPEN_STATUSES = EnumSet.of(
            ModificationStatus.REQUESTED, ModificationStatus.QUOTED,
            ModificationStatus.AWAITING_SUPPLEMENT, ModificationStatus.AWAITING_REFUND);

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ModificationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ModificationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "initiated_by", nullable = false, updatable = false)
    private InitiatedBy initiatedBy;

    @Column(name = "initiated_by_user_id", updatable = false)
    private UUID initiatedByUserId;

    @Column(nullable = false, updatable = false, length = 200)
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "requested_content", columnDefinition = "jsonb")
    private ModificationContent requestedContent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "quoted_content", columnDefinition = "jsonb")
    private ModificationContent quotedContent;

    @Column(name = "system_amount")
    private Integer systemAmount;

    @Column(name = "surcharge_amount")
    private Integer surchargeAmount;

    @Column(name = "discount_amount")
    private Integer discountAmount;

    @Column(name = "adjustment_reason", length = 200)
    private String adjustmentReason;

    @Column(name = "new_total_amount")
    private Integer newTotalAmount;

    @Column(name = "quoted_net_paid")
    private Integer quotedNetPaid;

    @Column(name = "quoted_diff")
    private Integer quotedDiff;

    @Column(name = "agreed_net_paid")
    private Integer agreedNetPaid;

    @Column(name = "agreed_diff")
    private Integer agreedDiff;

    @Column(name = "quoted_at")
    private OffsetDateTime quotedAt;

    @Column(name = "agreed_at")
    private OffsetDateTime agreedAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(name = "reject_reason", length = 200)
    private String rejectReason;

    @Column(name = "source_modification_id", updatable = false)
    private UUID sourceModificationId;

    public boolean isOpen() {
        return OPEN_STATUSES.contains(status);
    }
}
