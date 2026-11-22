package com.petsitter.domain.model.fulfill;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 上傳槽位（{@code care_log_media_uploads}，SD-FULFILL §3.3 / §5.1 第 16 段）。不用 auditing；id 由應用端先產生
 * （物件鍵含 uploadId，INSERT 時即需確定），時間欄一律由 Service 以注入 Clock 寫入。
 * 狀態轉移 {@code PENDING → COMPLETED / FAILED / EXPIRED} 一律走 Repository 的條件式 UPDATE（§3.3 互斥）。
 */
@Entity
@Table(name = "care_log_media_uploads")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceReportUpload implements Persistable<UUID> {

    public static final String PENDING = "PENDING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String DISCARDED = "DISCARDED";
    public static final String EXPIRED = "EXPIRED";

    @Id
    private UUID id;

    @Column(name = "visit_id", nullable = false, updatable = false)
    private UUID visitId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "sitter_id", nullable = false, updatable = false)
    private UUID sitterId;

    @Column(name = "media_type", nullable = false, updatable = false)
    private String mediaType;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Column(name = "declared_size", nullable = false, updatable = false)
    private int declaredSize;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "declared_sha256", nullable = false, updatable = false, columnDefinition = "char(64)")
    private String declaredSha256;

    @Column(name = "declared_duration_seconds", updatable = false)
    private Short declaredDurationSeconds;

    @Column(length = 100, updatable = false)
    private String caption;

    @Column(name = "object_key", nullable = false, updatable = false)
    private String objectKey;

    @Column(nullable = false)
    private String status;

    @Column(name = "received_at", nullable = false, updatable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "failure_status")
    private Short failureStatus;

    @Column(name = "failure_code", length = 48)
    private String failureCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "failure_payload", columnDefinition = "jsonb")
    private Map<String, Object> failurePayload;

    @Column(name = "object_deleted_at")
    private OffsetDateTime objectDeletedAt;

    @Column(name = "delete_attempts", nullable = false)
    private short deleteAttempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Transient
    @Builder.Default
    private boolean newEntity = true;

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }
}
