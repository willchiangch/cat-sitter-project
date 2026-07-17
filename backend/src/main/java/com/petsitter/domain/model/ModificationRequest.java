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
}