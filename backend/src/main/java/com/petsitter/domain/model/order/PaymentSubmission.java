package com.petsitter.domain.model.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 每次上傳一列，駁回不清空（SD-ORDER §5.1.1 第 9 段）。表無 updated_at，故不繼承基底。 */
@Entity
@Table(name = "payment_submissions")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "submitted_by", nullable = false, updatable = false)
    private UUID submittedBy;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    @Column(name = "last_five", nullable = false, updatable = false, columnDefinition = "char(5)")
    private String lastFive;

    @Column(name = "disclaimer_agreed_at", nullable = false, updatable = false)
    private OffsetDateTime disclaimerAgreedAt;

    @Column(name = "proof_object_key", nullable = false, updatable = false, length = 512)
    private String proofObjectKey;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    @Column(name = "proof_sha256", nullable = false, updatable = false, columnDefinition = "char(64)")
    private String proofSha256;

    @Column(name = "proof_content_type", nullable = false, updatable = false, length = 16)
    private String proofContentType;

    @Column(name = "proof_size_bytes", nullable = false, updatable = false)
    private int proofSizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubmissionStatus status;

    @Column(name = "reject_reason", length = 200)
    private String rejectReason;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    /** O14「目前這筆 submission 上傳時間」起算點（CQ-64）= 伺服器收到請求的 receivedAt。 */
    @Column(name = "submitted_at", nullable = false)
    private OffsetDateTime submittedAt;

    @Version
    @Column(nullable = false)
    private Integer version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
