package com.petsitter.domain.model;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.List;

@Entity
@Table(name = "modification_requests")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ModificationRequest extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Builder.Default
    @Column(nullable = false)
    private String status = "PENDING_REVIEW";

    @Column(name = "diff_amount")
    private Integer diffAmount;

    @Column(name = "requested_by", nullable = false)
    private String requestedBy; // OWNER, SITTER

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<OrderItem> payload;

    /**
     * 變更後的新日期清單 (ISO-8601 字串)，於發起變更時鎖定，確認變更時不再信任前端重送 (Zero-Trust)
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> dates;

    @Column(name = "refund_proof_url")
    private String refundProofUrl;

    @Column(name = "new_total_amount")
    private Integer newTotalAmount;

    /**
     * 訂單在發起變更前的原始狀態 (CONFIRMED / IN_PROGRESS)，供保母拒絕變更時回復
     */
    @Column(name = "previous_status")
    private String previousStatus;

    // ===== 補款 (diffAmount > 0) 的付款紀錄，PRD-016 C.3 / SD-016 §4.2 =====
    // 獨立存放，不覆寫 Order 上首次付款的憑證、後五碼、冪等鍵、免責同意時間與 paidAt

    /** 補款憑證私有 GCS Object Key (payment-proofs/...)，檢視一律走簽名網址 (SD-007 §4.7) */
    @Column(name = "supplement_proof_key")
    private String supplementProofKey;

    @Column(name = "supplement_proof_last_five", length = 5)
    private String supplementProofLastFive;

    @Column(name = "supplement_idempotency_key")
    private String supplementIdempotencyKey;

    /** 補款憑證提交時間，同時為該次線下交易免責聲明的同意時間 */
    @Column(name = "supplement_submitted_at")
    private java.time.OffsetDateTime supplementSubmittedAt;

    /** 保母核對補款入帳時間；非 null 代表此筆補款已結清 */
    @Column(name = "supplement_verified_at")
    private java.time.OffsetDateTime supplementVerifiedAt;
}