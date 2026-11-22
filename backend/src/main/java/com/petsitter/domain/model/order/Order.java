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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 訂單聚合根（SD-ORDER §5.1.1 第 1 段）。只有 {@code application.service.order} 可寫（{@code OrderAggregateArchTest}）。
 * 當事人以 UUID 欄保存，不建 JPA 關聯（顯示名稱以查詢取得，不快照）。
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class Order extends VersionedEntity {

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "sitter_id", nullable = false, updatable = false)
    private UUID sitterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "base_amount", nullable = false)
    private int baseAmount;

    @Column(name = "surcharge_amount")
    private Integer surchargeAmount;

    @Column(name = "discount_amount")
    private Integer discountAmount;

    @Column(name = "pricing_notes", length = 200)
    private String pricingNotes;

    /** 報價前為 NULL（ck_orders_quote）。 */
    @Column(name = "total_amount")
    private Integer totalAmount;

    @Column(name = "quoted_at")
    private OffsetDateTime quotedAt;

    /** O5；月接單額度計數依據（取消不返還）。 */
    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "review_expires_at", nullable = false)
    private OffsetDateTime reviewExpiresAt;

    /** 首日（Asia/Taipei 日期），O1 寫入後不變。 */
    @Column(name = "first_service_date", nullable = false)
    private LocalDate firstServiceDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "questionnaire_status", nullable = false)
    private QuestionnaireStatus questionnaireStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "questionnaire_exempt_reason")
    private QuestionnaireExemptReason questionnaireExemptReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancelled_by")
    private ActorRole cancelledBy;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason_code")
    private CancelReasonCode cancelReasonCode;

    @Column(name = "cancel_reason", length = 200)
    private String cancelReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "completed_by")
    private ActorRole completedBy;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    /** 有效行程全部結束後才非 NULL（SD-ORDER §3.1.3）。 */
    @Column(name = "completion_basis_at")
    private OffsetDateTime completionBasisAt;

    @Column(name = "disputed_at")
    private OffsetDateTime disputedAt;

    /** SD-CARE 使用（PRD-013）；只經 {@code OrderSnapshotService.markMediaExpiryWarned} 單欄更新。 */
    @Column(name = "media_expiry_warned", nullable = false)
    private boolean mediaExpiryWarned;

    public boolean isTerminal() {
        return status == OrderStatus.COMPLETED || status == OrderStatus.CANCELLED;
    }
}
