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

    @Column(name = "refund_proof_url")
    private String refundProofUrl;
}